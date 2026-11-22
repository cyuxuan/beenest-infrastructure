package club.beenest.iam.authz.grant;

import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.authz.authorization.ApplicationVisibilityService;
import club.beenest.iam.authz.principal.IamPrincipalFactory;
import club.beenest.iam.authz.client.IamRegisteredClientRepository;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.web.IamIssuerBaseUrlResolver;
import club.beenest.iam.common.web.IamRateLimiter;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.federation.broker.IdentityBrokerService;
import club.beenest.iam.persistence.entity.ApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 凭据类提供方的登录服务:把第三方凭据换成一个授权码。
 * <p>链路:客户端认证 → 凭据换成外部身份 → 建号/复用账号 → 签发授权码。</p>
 * <p><b>只签授权码,不自己签令牌</b>:令牌一律由标准授权码流程签发。
 * 协议栈那条路是现成且被完整验证过的(签发、刷新、吊销、userinfo 都跑得通),
 * 自己另起一套签发逻辑,等于把协议栈已经解决过的问题重新踩一遍。</p>
 */
@Service
public class ProviderCodeService {

    /** 授权码有效期:与标准授权码流程一致,五分钟内必须换令牌。 */
    private static final Duration CODE_TTL = Duration.ofMinutes(5);

    /** 登录方式标识:写入登录事件,便于审计区分入口。 */
    private static final String LOGIN_TYPE = "PROVIDER";

    /** 来源地址限流的场景标识。 */
    private static final String SCENE_IP = "provider-code-ip";

    /** 客户端密钥错误限流的场景标识:按客户端标识计,与来源地址无关。 */
    private static final String SCENE_CLIENT_SECRET = "provider-code-client-secret";

    /** 同一客户端每分钟允许的密钥错误次数。 */
    private static final int CLIENT_SECRET_FAILURE_LIMIT = 20;

    private final IdentityBrokerService brokerService;
    private final IamPrincipalFactory principalFactory;
    private final LoginEventPublisher loginEventPublisher;
    private final RegisteredClientRepository registeredClientRepository;
    private final OAuth2AuthorizationService authorizationService;
    private final PasswordEncoder passwordEncoder;
    private final IamIssuerBaseUrlResolver issuerBaseUrlResolver;
    /** 应用可见性判定:凭据直换授权码绕过了授权端点,权限门槛要在这里补一次 */
    private final ApplicationVisibilityService visibilityService;
    /** 是否强制应用可见性;与授权端点共用同一开关 */
    private final boolean enforceApplicationVisibility;
    /** 限流:未登录可达的接口按来源地址限速 */
    private final IamRateLimiter rateLimiter;
    private final SecureRandom secureRandom = new SecureRandom();

    public ProviderCodeService(IdentityBrokerService brokerService,
                               IamPrincipalFactory principalFactory,
                               LoginEventPublisher loginEventPublisher,
                               RegisteredClientRepository registeredClientRepository,
                               OAuth2AuthorizationService authorizationService,
                               PasswordEncoder passwordEncoder,
                               IamIssuerBaseUrlResolver issuerBaseUrlResolver,
                               ApplicationVisibilityService visibilityService,
                               @Value("${iam.authorization.enforce-application-visibility:true}")
                               boolean enforceApplicationVisibility,
                               IamRateLimiter rateLimiter) {
        this.brokerService = brokerService;
        this.principalFactory = principalFactory;
        this.loginEventPublisher = loginEventPublisher;
        this.registeredClientRepository = registeredClientRepository;
        this.authorizationService = authorizationService;
        this.passwordEncoder = passwordEncoder;
        this.issuerBaseUrlResolver = issuerBaseUrlResolver;
        this.visibilityService = visibilityService;
        this.enforceApplicationVisibility = enforceApplicationVisibility;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 凭据换授权码。
     *
     * @param providerAlias 提供方别名
     * @param credentials   第三方凭据(键名由提供方定义)
     * @param clientId      客户端标识
     * @param clientSecret  客户端密钥
     * @param codeChallenge PKCE 挑战值(客户端要求 PKCE 时必填)
     * @param request       当前请求(推导签发方与留痕来源)
     * @return 授权码与回调地址
     */
    public IssuedCode issueCode(String providerAlias, Map<String, String> credentials,
                                 String clientId, String clientSecret, String codeChallenge,
                                 HttpServletRequest request) {
        // 1. 限流:该端点未登录即可达,又是外部调用的放大器
        rateLimiter.check(SCENE_IP, request.getRemoteAddr(), 60, Duration.ofMinutes(1));
        // 2. 客户端认证:密钥错在这里就拒,不消耗用户的第三方凭据
        RegisteredClient client;
        try {
            client = authenticateClient(clientId, clientSecret);
        } catch (BusinessException e) {
            // 密钥错误要留痕,并按客户端维度计数:
            // 攻击者轮换来源地址时,来源维度的限流会被绕开,这一层不会
            auditFailure(request, clientId, e.getMessage());
            rateLimiter.check(SCENE_CLIENT_SECRET, clientId,
                    CLIENT_SECRET_FAILURE_LIMIT, Duration.ofMinutes(1));
            throw e;
        }
        // 3. 客户端要有授权码流程的资格:平台签的是授权码,换令牌走的是标准授权码流程,
        //    没登记这条流程的客户端不能从这条口子拿到用户令牌
        if (!client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.AUTHORIZATION_CODE)) {
            String reason = "该客户端未获授权使用授权码流程";
            auditFailure(request, clientId, reason);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, reason);
        }
        try {
            return issueCodeFor(providerAlias, credentials, client, codeChallenge, request);
        } catch (BusinessException e) {
            // 第三方不可用、凭据无效、账号被禁用等失败同样要留痕
            auditFailure(request, clientId, e.getMessage());
            throw e;
        }
    }

    /**
     * 凭据换成外部身份,再签发授权码。
     *
     * @param providerAlias 提供方别名
     * @param credentials   第三方凭据(键名由提供方定义)
     * @param client        已认证的客户端
     * @param codeChallenge PKCE 挑战值(客户端要求 PKCE 时必填)
     * @param request       当前请求(推导签发方与留痕来源)
     * @return 授权码与回调地址
     */
    private IssuedCode issueCodeFor(String providerAlias, Map<String, String> credentials,
                                    RegisteredClient client, String codeChallenge,
                                    HttpServletRequest request) {
        // 1. 凭据换成外部身份,首次登录自动建号。
        //    凭据的键名由提供方自己定义,平台原样转交,不解释其含义
        RealmModel realm = currentRealm();
        UserModel user = brokerService.completeLogin(realm.realmKey(), providerAlias,
                new CallbackContext(null, null, realm.realmKey(), null, credentials));
        if (user.status() != 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "账号已禁用或锁定");
        }
        // 2. 主体:权限口径与其它登录方式一致。
        //    凭据直换授权码没有"第一因素"的概念,认证方式按未知处理
        IamUserPrincipal principal = principalFactory.build(realm, user, null);
        Authentication principalAuthentication = principalFactory.toAuthentication(principal);
        // 2.5 应用可见性:标准授权码流程在授权端点有闸门,这条口子不经过那里,
        //     否则知道 client_id 与密钥的人可以用任意账号换到绑定应用的令牌
        if (enforceApplicationVisibility) {
            ApplicationService app = visibilityService.boundApplication(realm.id(), client);
            if (app != null && !visibilityService.canAccess(principal, app)) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "账号无权访问应用: " + app.getAppCode());
            }
        }
        // 2.6 二步验证:这条口子不经过登录页,拿不到第二因素;要求 MFA 的客户端必须拒绝
        if (IamRegisteredClientRepository.policyOf(client).requiresMfa()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "该客户端要求二步验证,不能使用凭据直换授权码");
        }
        // 3. 写一条授权记录:授权码 + OIDC 授权请求 + 主体快照。
        //    之后由标准授权码流程换令牌,签发、刷新、吊销全部走协议栈
        String code = generateCode();
        Instant issuedAt = Instant.now();
        String redirectUri = firstRedirectUri(client);
        // 客户端强制 PKCE 时,挑战值必须带上:协议栈换令牌时要拿它核对校验值
        if (client.getClientSettings().isRequireProofKey() && (codeChallenge == null || codeChallenge.isBlank())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "该客户端要求 PKCE,凭据登录必须携带 code_challenge");
        }
        OAuth2AuthorizationRequest oidcRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(issuer(request) + "/oauth2/authorize")
                .clientId(client.getClientId())
                .redirectUri(redirectUri)
                .scopes(scopesOf(client))
                .state(UUID.randomUUID().toString())
                .additionalParameters(parameters -> {
                    if (codeChallenge != null && !codeChallenge.isBlank()) {
                        parameters.put("code_challenge", codeChallenge);
                        parameters.put("code_challenge_method", "S256");
                    }
                })
                .attributes(attributes -> attributes.put(Principal.class.getName(), principalAuthentication))
                .build();
        OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(principalAuthentication.getName())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(scopesOf(client))
                .attribute(Principal.class.getName(), principalAuthentication)
                .attribute(OAuth2AuthorizationRequest.class.getName(), oidcRequest);
        if (codeChallenge != null && !codeChallenge.isBlank()) {
            // 协议栈换令牌时从授权记录的属性里取挑战值
            builder.attribute("code_challenge", codeChallenge);
        }
        builder.token(new OAuth2AuthorizationCode(code, issuedAt, issuedAt.plus(CODE_TTL)),
                metadata -> { });
        authorizationService.save(builder.build());
        // 4. 留痕:与其它登录方式同一套风控与审计口径
        loginEventPublisher.publish(realm.realmKey(), user.id(), user.username(),
                LoginEventEvent.LOGIN_SUCCESS, LOGIN_TYPE,
                request.getRemoteAddr(), request.getHeader("User-Agent"), null);
        return new IssuedCode(code, redirectUri);
    }

    /**
     * 记录登录失败留痕。
     * <p>凭据登录是未登录可达的入口,客户端密钥错误、第三方不可用、凭据无效等
     * 原因逐条落库,供事后追责与风控查询。</p>
     *
     * @param request  当前请求,提供来源 IP 与 User-Agent
     * @param clientId 客户端标识
     * @param reason   失败原因
     */
    private void auditFailure(HttpServletRequest request, String clientId, String reason) {
        RealmContext context = RealmContextHolder.get();
        loginEventPublisher.publish(context == null ? null : context.realmKey(), null, clientId,
                LoginEventEvent.LOGIN_FAILURE, LOGIN_TYPE,
                request.getRemoteAddr(), request.getHeader("User-Agent"), reason);
    }

    /**
     * 以客户端凭据完成机密客户端认证。
     * <p>复用协议栈的 ClientSecret 认证器:库中保存的是密钥哈希,比对必须经平台的口令编码器。</p>
     *
     * @param clientId     客户端标识
     * @param clientSecret 客户端密钥明文
     * @return 认证通过的客户端注册信息
     * @throws BusinessException 凭据无效或客户端不存在,错误码 UNAUTHORIZED
     */
    private RegisteredClient authenticateClient(String clientId, String clientSecret) {
        ClientSecretAuthenticationProvider provider =
                new ClientSecretAuthenticationProvider(registeredClientRepository, authorizationService);
        provider.setPasswordEncoder(passwordEncoder);
        try {
            Authentication authenticated = provider.authenticate(
                    new OAuth2ClientAuthenticationToken(clientId, ClientAuthenticationMethod.CLIENT_SECRET_POST,
                            clientSecret, null));
            // 认证结果里带的是客户端认证凭据,注册客户端要从它身上取
            if (authenticated instanceof OAuth2ClientAuthenticationToken clientAuthentication) {
                return clientAuthentication.getRegisteredClient();
            }
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "客户端凭据无效");
        } catch (OAuth2AuthenticationException e) {
            // 对外统一为凭据无效,不区分"客户端不存在"与"密钥错误"
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "客户端凭据无效");
        }
    }

    /**
     * 取客户端登记的第一个回调地址。
     *
     * @param client 客户端注册信息
     * @return 回调地址;未登记时返回 oob 占位值
     */
    private static String firstRedirectUri(RegisteredClient client) {
        return client.getRedirectUris().stream().findFirst().orElse("urn:ietf:wg:oauth:2.0:oob");
    }

    /**
     * 取客户端登记的授权范围。
     *
     * @param client 客户端注册信息
     * @return 授权范围集合;客户端未登记时返回 openid 与 profile
     */
    private static Set<String> scopesOf(RegisteredClient client) {
        if (client.getScopes() == null || client.getScopes().isEmpty()) {
            return new LinkedHashSet<>(Set.of("openid", "profile"));
        }
        return new LinkedHashSet<>(client.getScopes());
    }

    /**
     * 拼令牌签发方地址,与协议端点保持一致。
     *
     * @param request 当前请求,用于解析外部访问基址
     * @return 形如 {@code https://iam.example.com/realms/{realmKey}} 的签发方地址
     */
    private String issuer(HttpServletRequest request) {
        return issuerBaseUrlResolver.resolve(request) + "/realms/" + currentRealm().realmKey();
    }

    /**
     * 生成授权码。
     *
     * @return 32 字节随机数 URL-Safe Base64 编码后的不可推导字符串
     */
    private String generateCode() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 取当前请求上下文所属身份域。
     *
     * @return 当前身份域
     * @throws BusinessException 上下文缺失或身份域未指定,错误码 NOT_FOUND
     */
    private static RealmModel currentRealm() {
        RealmContext context = RealmContextHolder.get();
        if (context == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "身份域不存在或未指定");
        }
        return context.realm();
    }

    /**
     * 签发结果。
     *
     * @param code        授权码
     * @param redirectUri 换令牌时必须携带的同一个回调地址
     */
    public record IssuedCode(String code, String redirectUri) {
    }
}
