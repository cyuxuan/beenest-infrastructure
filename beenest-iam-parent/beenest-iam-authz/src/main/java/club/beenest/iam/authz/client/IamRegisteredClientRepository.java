package club.beenest.iam.authz.client;

import club.beenest.iam.authz.admission.ClientAdmissionPolicy;
import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.core.spi.provider.RealmProvider;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 客户端仓储实现。
 * <p>client_id 即 clientKey;按当前请求域过滤,同名客户端在不同域互不影响。
 * 客户端的增删改由管理端 API 直接维护 iam_client,本仓储只做读取映射。</p>
 */
@Component
public class IamRegisteredClientRepository implements RegisteredClientRepository {

    private static final Logger log = LoggerFactory.getLogger(IamRegisteredClientRepository.class);

    /** 准入白名单在 ClientSettings 中的键名。 */
    public static final String SETTING_ALLOWED_LOGIN_TYPES = "iam.allowed_login_types";
    /** 强制 MFA 在 ClientSettings 中的键名。 */
    public static final String SETTING_REQUIRE_MFA = "iam.require_mfa";

    private final ClientProvider clientProvider;
    private final RealmProvider realmProvider;

    public IamRegisteredClientRepository(ClientProvider clientProvider, RealmProvider realmProvider) {
        this.clientProvider = clientProvider;
        this.realmProvider = realmProvider;
    }

    /**
     * 从 RegisteredClient 读出准入策略。
     * <p>策略随客户端对象下发,判定路径不必再查一次库。</p>
     * <p>配置解析失败时按拒绝一切返回:读不懂限制却给出"无限制",会静默解除
     * 一条本该生效的限制,且结果看似正常、不产生告警。</p>
     *
     * @param registeredClient 客户端注册信息,可为 null
     * @return 准入策略;对象缺失或白名单无法解析时返回拒绝一切策略
     */
    public static ClientAdmissionPolicy policyOf(RegisteredClient registeredClient) {
        if (registeredClient == null || registeredClient.getClientSettings() == null) {
            // SAS 的 Builder 总会给出默认 ClientSettings,调用方也先取到客户端;
            // 为空属于调用异常,同样按拒绝一切处理
            log.warn("客户端对象或其 ClientSettings 为空,准入策略按拒绝一切处理");
            return ClientAdmissionPolicy.denyAllPolicy();
        }
        String types = registeredClient.getClientSettings().getSetting(SETTING_ALLOWED_LOGIN_TYPES);
        Set<LoginMethod> methods = new LinkedHashSet<>();
        if (types != null && !types.isBlank()) {
            for (String code : types.split(",")) {
                LoginMethod method = LoginMethod.fromCode(code);
                if (method != null) {
                    methods.add(method);
                } else if (!code.isBlank()) {
                    log.warn("客户端 {} 的准入白名单含无法识别的方式:{},该条已忽略",
                            registeredClient.getClientId(), code);
                }
            }
            if (methods.isEmpty()) {
                // 白名单一条都认不出来属于配置损坏,按拒绝一切处理;
                // 落回 unrestricted 会静默解除限制,典型触发是 LoginMethod
                // 枚举值被移除或改名
                log.error("客户端 {} 的准入白名单无法解析,已按拒绝一切处理:{}",
                        registeredClient.getClientId(), types);
                return ClientAdmissionPolicy.denyAllPolicy();
            }
        }
        Boolean requireMfa = registeredClient.getClientSettings().getSetting(SETTING_REQUIRE_MFA);
        return new ClientAdmissionPolicy(methods, Boolean.TRUE.equals(requireMfa));
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        // 1. 解析当前域(协议端点均携带域上下文)
        RealmModel realm = currentRealm();
        if (realm == null) {
            return null;
        }
        // 2. 域内查找并映射
        return clientProvider.findByClientKey(realm.id(), clientId)
                .filter(ClientModel::enabled)
                .map(model -> toRegisteredClient(model, realm))
                .orElse(null);
    }

    @Override
    public RegisteredClient findById(String id) {
        // 1. 主键即数据库行 id
        long dbId;
        try {
            dbId = Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
        // 2. 读取并确认与当前域一致
        ClientModel model = clientProvider.findById(dbId).orElse(null);
        if (model == null || !model.enabled()) {
            return null;
        }
        RealmModel realm = currentRealm();
        if (realm == null || !realm.id().equals(model.realmId())) {
            return null;
        }
        return toRegisteredClient(model, realm);
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        // 客户端写入走管理端 API,此处不支持
        throw new UnsupportedOperationException("客户端由管理端 API 维护");
    }

    /**
     * 领域模型转换为 SAS RegisteredClient。
     *
     * @param model 客户端领域模型
     * @param realm 客户端所属身份域,提供令牌有效期默认值
     * @return 协议层客户端对象
     */
    private RegisteredClient toRegisteredClient(ClientModel model, RealmModel realm) {
        RegisteredClient.Builder builder = RegisteredClient.withId(String.valueOf(model.id()))
                .clientId(model.clientKey())
                .clientName(model.clientName())
                .redirectUris(uris -> uris.addAll(model.redirectUris()))
                // 登出回跳白名单:OIDC 登出端点据此校验 post_logout_redirect_uri,
                // 未登记的地址一律拒绝,避免登出链路变成开放重定向
                .postLogoutRedirectUris(uris -> uris.addAll(model.postLogoutRedirectUris()))
                .scopes(scopes -> scopes.addAll(model.scopes()))
                // 公共客户端无密钥,须显式声明 none 认证方式,令牌端点据此匹配该客户端;
                // 机密客户端走 Basic/Post 两种凭证传递方式
                .clientAuthenticationMethods(methods -> {
                    if (model.clientType() == ClientModel.ClientType.CONFIDENTIAL) {
                        methods.add(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
                        methods.add(ClientAuthenticationMethod.CLIENT_SECRET_POST);
                    } else {
                        methods.add(ClientAuthenticationMethod.NONE);
                    }
                })
                // 是否要求用户授权同意:第三方接入开启后首次授权展示同意页,同意记录可管理端撤销
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(model.requirePkce())
                        .requireAuthorizationConsent(model.requireConsent())
                        // 准入策略随客户端下发:协议链判定时不必再查库
                        .setting(SETTING_ALLOWED_LOGIN_TYPES, String.join(",", model.allowedLoginTypes()))
                        .setting(SETTING_REQUIRE_MFA, model.requireMfa())
                        .build())
                // 令牌有效期:客户端覆盖值优先,缺省回落域默认值
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofSeconds(
                                model.resolveAccessTokenTtl(realm.accessTokenTtl())))
                        .refreshTokenTimeToLive(Duration.ofSeconds(
                                model.resolveRefreshTokenTtl(realm.refreshTokenTtl())))
                        // 复用模式刷新不换发新令牌,容忍多标签页并发刷新,泄露的令牌在有效期内可重复使用;
                        // 轮换模式旧令牌刷新即失效,并发刷新时后提交的一支会失效。
                        // 默认复用,按客户端开启轮换
                        .reuseRefreshTokens(model.reuseRefreshTokens())
                        .build());
        // 授权类型按客户端配置授予:authorization_code / refresh_token 为交互式接入;
        // client_credentials 为机器对机器,仅在绑定服务账号主体时授予,
        // 无主体的凭据模式令牌无法定位权限归属
        for (String grantType : model.grantTypes()) {
            switch (grantType) {
                case "authorization_code" -> builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
                case "refresh_token" -> builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
                case "client_credentials" -> {
                    if (model.supportsClientCredentials()) {
                        builder.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
                    }
                }
                default -> log.debug("客户端 {} 配置了未知授权类型,已忽略: {}", model.clientKey(), grantType);
            }
        }
        if (model.secret() != null && !model.secret().isBlank()) {
            builder.clientSecret(model.secret());
        }
        return builder.build();
    }

    /**
     * 取当前请求上下文所属身份域。
     *
     * @return 上下文中的身份域;无上下文时返回 default 域,该域也不存在时返回 null
     */
    private RealmModel currentRealm() {
        RealmContext context = RealmContextHolder.get();
        if (context != null) {
            return context.realm();
        }
        return realmProvider.findByRealmKey("default").orElse(null);
    }
}
