package club.beenest.iam.authz.authorization;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.IamClientSessionService;
import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.util.RedisUtils;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Redis 授权存储。
 * <p>正向键 {@code iam:authz:{id}} 存投影 JSON;反向键 {@code iam:authz:token:{tokenValue}}
 * 存授权 id,供按令牌值定位;用户维键 {@code iam:authz:user:{userId}} 存授权 id 集合,
 * 供管理端按用户列举与吊销。RegisteredClient 不入 JSON,读取时由客户端仓储按 id 回填。</p>
 */
@Component
public class RedisOAuth2AuthorizationService implements OAuth2AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(RedisOAuth2AuthorizationService.class);

    /** 请求级属性名:授权码抢占标记的持有者标识 */
    private static final String CLAIM_OWNER_ATTRIBUTE = RedisOAuth2AuthorizationService.class.getName() + ".owner";
    /** 无请求上下文时写入的持有者标识 */
    private static final String NO_REQUEST_OWNER = "-";

    /**
     * 反序列化白名单:仅允许本项目、Spring Security 与 JDK 值/集合类型。
     * <p>安全模块默认只放行 Spring Security 自身的类型,本项目投影类型与 JDK 不可变集合
     * 会被拒绝;这里用正向白名单(而非关闭校验)扩展出必要的范围。</p>
     * <p>白名单经 {@code SecurityJacksonModules.getModules(loader, builder)} 合并进
     * 安全模块自身的类型表,默认类型标记(NON_FINAL + As.PROPERTY)由该组件统一激活。</p>
     */
    private static final String[] ALLOWED_TYPE_PREFIXES = {
            "club.beenest.iam.",
            "org.springframework.security.",
            "java.util.",
            "java.time."
    };

    /**
     * 需按类型精确放行的类。
     * <p>JWT 声明中的 issuer 在 Spring Security 里以 {@link java.net.URL} 承载,不在上述
     * 包前缀内;按前缀匹配会连带放行同前缀的其它类型,故按类放行。</p>
     * <p>{@code java.lang} 整包放行会将其下可实例化类一并交给反序列化器;声明为 Object
     * 的字段里只出现基础值类型,这里只放行实际会出现的那几个值类型。</p>
     */
    private static final Class<?>[] ALLOWED_EXACT_TYPES = {
            java.net.URL.class,
            Long.class,
            Integer.class,
            Boolean.class,
            Double.class,
            Float.class,
            Short.class,
            Byte.class,
            Character.class
    };

    private final RegisteredClientRepository registeredClientRepository;
    private final RedisUtils redisUtils;
    /** 客户端会话记录:授权码签发时登记会话到应用的映射,供单点登出广播寻址 */
    private final IamClientSessionService clientSessionService;
    /** 专用映射器:注册安全模块(Jackson 3),支持属性中的认证主体/授权请求往返 */
    private final ObjectMapper objectMapper;

    public RedisOAuth2AuthorizationService(RegisteredClientRepository registeredClientRepository,
                                           RedisUtils redisUtils,
                                           IamClientSessionService clientSessionService) {
        this.registeredClientRepository = registeredClientRepository;
        this.redisUtils = redisUtils;
        this.clientSessionService = clientSessionService;
        // 安全模块集合内含 SAS 授权模块与默认类型激活(NON_FINAL + As.PROPERTY),
        // 本项目白名单经 builder 合并;java.time 支持在 Jackson 3 已内置,无需 JavaTimeModule
        this.objectMapper = JsonMapper.builder()
                .addModules(SecurityJacksonModules.getModules(getClass().getClassLoader(),
                        allowlistedValidatorBuilder()))
                .build();
    }

    /**
     * 构建带白名单的多态类型校验器。
     * <p>沿用安全模块按非 final 类型写类型标记的规则,与本项目投影类型的 final 声明配合,
     * 使自有类型以纯 JSON 形态存取;类型标记的写入规则由安全模块统一激活,这里只定白名单。</p>
     *
     * @return 已加入包前缀白名单与精确类型白名单的校验器构造器
     */
    private static BasicPolymorphicTypeValidator.Builder allowlistedValidatorBuilder() {
        BasicPolymorphicTypeValidator.Builder validator = BasicPolymorphicTypeValidator.builder();
        for (String prefix : ALLOWED_TYPE_PREFIXES) {
            validator.allowIfSubType(prefix);
        }
        for (Class<?> type : ALLOWED_EXACT_TYPES) {
            validator.allowIfSubType(type);
        }
        return validator;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        // 读取库中已有投影,不经 RegisteredClient 回填,客户端解析失败时仍保留索引清理依据
        AuthorizationData stored = readData(authorization.getId());
        // 授权码首次签发时登记会话到应用的映射,单点登出据此寻址;令牌交换与刷新重复保存时不登记
        recordClientSessionIfCodeIssued(authorization, stored);
        // SAS 7 的吊销语义是标记失效并保存,不再调用 remove();持有型令牌被标记失效时
        // 整条授权作废,失效需落到黑名单与授权记录,被吊销的访问令牌才不会凭无状态验签继续通过
        if (hasRevokedGrantToken(authorization)) {
            revokeAuthorization(authorization, stored);
            return;
        }
        // 旧访问令牌被替换(刷新令牌轮换):旧令牌立即失效,避免新旧并存
        blocklistReplacedAccessToken(authorization, stored);
        // 已存在的授权先清理旧反向索引(如刷新流程重复保存)
        if (stored != null) {
            removeIndexKeys(stored);
        }
        // 投影并写入正向键
        AuthorizationData data = toData(authorization);
        Duration ttl = ttlOf(data);
        redisUtils.set(String.format(IamRedisKeyConstants.OAUTH2_AUTHORIZATION, data.getId()),
                write(data), ttl);
        // 写入反向索引:令牌值定位授权 id
        String id = data.getId();
        putIndex(data.getAccessToken(), id);
        putIndex(data.getRefreshToken(), id);
        putIndex(data.getIdToken(), id);
        putIndex(data.getAuthorizationCode(), id);
        // 写入用户维索引:用户到授权 id 集合,管理端按用户列举与吊销的依据
        Long userId = data.getPrincipal() == null ? null : data.getPrincipal().getUserId();
        if (userId != null) {
            redisUtils.addToSetWithTtl(userIndexKey(userId), ttl, id);
        }
        // 写入 state 索引:授权同意流程按 state 回查授权请求,缺少该索引时
        // 同意提交会因找不到授权被判 invalid_request
        putStateIndex(data.getAttributes(), id, ttl);
    }

    /**
     * 授权的持有型令牌是否已被标记失效。
     * <p>只认访问令牌与刷新令牌:授权码在正常换取令牌时同样会被标记失效
     * (一次性语义),一并作废整条授权会误杀正常流程。</p>
     *
     * @param authorization 待判定的授权
     * @return true 表示访问令牌或刷新令牌已被标记失效
     */
    private static boolean hasRevokedGrantToken(OAuth2Authorization authorization) {
        OAuth2Authorization.Token<OAuth2AccessToken> access = authorization.getToken(OAuth2AccessToken.class);
        if (access != null && access.isInvalidated()) {
            return true;
        }
        OAuth2Authorization.Token<OAuth2RefreshToken> refresh = authorization.getToken(OAuth2RefreshToken.class);
        return refresh != null && refresh.isInvalidated();
    }

    /**
     * 作废整条授权:访问令牌进黑名单,授权记录与索引一并清理。
     * <p>授权记录删除后刷新时查不到授权,刷新令牌随之失效。</p>
     *
     * @param authorization 被标记失效的授权
     * @param stored        库中已有投影;为 null 时按 authorization 现算投影清理索引
     */
    private void revokeAuthorization(OAuth2Authorization authorization, AuthorizationData stored) {
        // 1. 访问令牌进黑名单:无状态验签据此立即拒绝
        OAuth2Authorization.Token<OAuth2AccessToken> access = authorization.getToken(OAuth2AccessToken.class);
        if (access != null) {
            blocklistAccessTokenValue(access.getToken().getTokenValue(), access.getToken().getExpiresAt());
        } else if (stored != null && stored.getAccessToken() != null) {
            blocklistAccessTokenValue(stored.getAccessToken().getTokenValue(),
                    stored.getAccessToken().getExpiresAt());
        }
        // 2. 授权记录作废:正向键与全部索引清理
        String id = authorization.getId();
        redisUtils.delete(String.format(IamRedisKeyConstants.OAUTH2_AUTHORIZATION, id));
        removeIndexKeys(stored != null ? stored : toData(authorization));
        removeUserIndex(authorization);
    }

    /**
     * 旧访问令牌被替换时(刷新令牌换取新令牌)把旧令牌写入黑名单。
     * <p>刷新后新旧访问令牌并存会让已轮换的旧令牌继续可用,与 userinfo 端点的
     * 判定口径不一致。</p>
     *
     * @param authorization 本次保存的授权
     * @param stored        库中已有投影;无旧访问令牌或新旧值相同时不处理
     */
    private void blocklistReplacedAccessToken(OAuth2Authorization authorization, AuthorizationData stored) {
        if (stored == null || stored.getAccessToken() == null) {
            return;
        }
        String oldValue = stored.getAccessToken().getTokenValue();
        if (oldValue == null || oldValue.isBlank()) {
            return;
        }
        OAuth2Authorization.Token<OAuth2AccessToken> current = authorization.getToken(OAuth2AccessToken.class);
        String currentValue = current == null ? null : current.getToken().getTokenValue();
        if (oldValue.equals(currentValue)) {
            return;
        }
        blocklistAccessTokenValue(oldValue, stored.getAccessToken().getExpiresAt());
    }

    /**
     * 授权码签发时记录客户端会话。
     * <p>判定口径:授权类型为授权码、本次保存带 code 且库中此前没有这条授权的 code。
     * 令牌交换与刷新再次保存同一授权时 code 已存在或已被消费,不重复记录。</p>
     * <p>此前是否已有 code 以库中投影为准,不依赖 RegisteredClient 回填:回填失败返回
     * null,据此判断会把已签发的会话重复登记。</p>
     *
     * @param authorization 本次保存的授权
     * @param stored        库中已有投影;用于判定 code 是否首次出现
     */
    private void recordClientSessionIfCodeIssued(OAuth2Authorization authorization,
                                                 AuthorizationData stored) {
        if (!AuthorizationGrantType.AUTHORIZATION_CODE.equals(authorization.getAuthorizationGrantType())) {
            return;
        }
        if (authorization.getToken(OAuth2AuthorizationCode.class) == null) {
            return;
        }
        if (stored != null && stored.getAuthorizationCode() != null) {
            return;
        }
        // 主体从属性中取;客户端 id 在 RegisteredClient 里就是 iam_client.id
        Object principalAttribute = authorization.getAttributes().get(Principal.class.getName());
        if (!(principalAttribute instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof IamUserPrincipal user)) {
            return;
        }
        Long clientDbId;
        try {
            clientDbId = Long.parseLong(authorization.getRegisteredClientId());
        } catch (NumberFormatException e) {
            return;
        }
        clientSessionService.recordCurrentSession(user, clientDbId, authorization.getId());
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        // 1. 删除正向键与反向索引
        redisUtils.delete(String.format(IamRedisKeyConstants.OAUTH2_AUTHORIZATION, authorization.getId()));
        removeIndexKeys(authorization);
        // 2. 清理用户维索引
        removeUserIndex(authorization);
        // 3. 吊销即全局生效:访问令牌是无状态 JWT,仅删授权记录不足以让
        //    已签发令牌在业务接口侧立即失效(那里只验签不查授权库)。
        //    将 jti 写入黑名单(存活期与令牌一致),验签环节据此拒绝
        OAuth2Authorization.Token<OAuth2AccessToken> access =
                authorization.getToken(OAuth2AccessToken.class);
        if (access != null) {
            blocklistAccessTokenValue(access.getToken().getTokenValue(), access.getToken().getExpiresAt());
        }
    }

    /**
     * 将访问令牌的 jti 写入黑名单。
     * <p>只解析不验签:令牌值来自本存储,伪造值在解码器验签阶段必然失败;黑名单键的
     * TTL 与令牌剩余存活期一致,过期后由 JWT 自身的 exp 校验拒绝,无需常驻。</p>
     *
     * @param tokenValue 访问令牌原文;为空或无 jti 时不写入
     * @param expiresAt  令牌过期时间,用于计算黑名单 TTL
     */
    private void blocklistAccessTokenValue(String tokenValue, Instant expiresAt) {
        if (tokenValue == null || tokenValue.isBlank()) {
            return;
        }
        try {
            SignedJWT jwt = SignedJWT.parse(tokenValue);
            String jti = jwt.getJWTClaimsSet().getJWTID();
            if (jti == null || jti.isBlank()) {
                return;
            }
            redisUtils.set(String.format(IamRedisKeyConstants.TOKEN_BLOCKLIST_JTI, jti),
                    "1", ttlOfToken(expiresAt));
        } catch (Exception e) {
            // 非 JWT 形态的访问令牌(不携带 jti)无法按 jti 吊销:
            // 刷新链路仍会因授权记录已删而失效,此处仅记录不阻断。
            // 不打印令牌原文:debug 日志常在排障时被临时打开,而访问令牌
            // 在有效期内可直接重放,落盘即等于泄露一个可用凭据
            log.debug("访问令牌无 jti,跳过黑名单: reason={}", e.getMessage());
        }
    }

    /**
     * 从用户维索引中移除授权 id。
     *
     * @param authorization 待移除的授权;属性中无用户主体时不处理
     */
    private void removeUserIndex(OAuth2Authorization authorization) {
        Object principalAttribute = authorization.getAttributes().get(Principal.class.getName());
        if (principalAttribute instanceof Authentication authentication
                && authentication.getPrincipal() instanceof IamUserPrincipal user) {
            redisUtils.removeFromSet(userIndexKey(user.getUserId()), authorization.getId());
        }
    }

    /**
     * 列举用户的全部有效授权(管理端令牌列表)。
     * <p>经用户维索引取授权 id,逐条回查投影;已过期被 Redis 自然清理的授权会在此处
     * 被过滤,返回的即当前仍有效的授权。</p>
     *
     * @param userId 用户主键
     * @return 授权列表,按最近签发时间倒序
     */
    public List<OAuth2Authorization> listByUser(Long userId) {
        List<String> ids = redisUtils.getSetAsList(userIndexKey(userId));
        if (ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .map(this::findById)
                .filter(java.util.Objects::nonNull)
                .sorted((a, b) -> issuedAtOf(b).compareTo(issuedAtOf(a)))
                .toList();
    }

    /**
     * 按授权 id 吊销(管理端单条吊销)。
     *
     * @param id 授权 id
     * @return true 表示授权存在且已删除;false 表示授权不存在或已过期
     */
    public boolean removeById(String id) {
        OAuth2Authorization authorization = findById(id);
        if (authorization == null) {
            return false;
        }
        remove(authorization);
        return true;
    }

    /**
     * 授权的访问令牌签发时间,供 listByUser 倒序排序。
     *
     * @param authorization 待取时间的授权
     * @return 访问令牌签发时间;无访问令牌或无签发时间时返回 {@link Instant#EPOCH}
     */
    private static Instant issuedAtOf(OAuth2Authorization authorization) {
        OAuth2Authorization.Token<OAuth2AccessToken> access =
                authorization.getToken(OAuth2AccessToken.class);
        if (access != null && access.getToken().getIssuedAt() != null) {
            return access.getToken().getIssuedAt();
        }
        return Instant.EPOCH;
    }

    /**
     * 用户维索引键。
     *
     * @param userId 用户主键
     * @return Redis 键
     */
    private static String userIndexKey(Long userId) {
        return String.format(IamRedisKeyConstants.OAUTH2_AUTHORIZATION_USER, userId);
    }

    @Override
    public OAuth2Authorization findById(String id) {
        // 1. 取投影 JSON
        AuthorizationData data = readData(id);
        if (data == null) {
            return null;
        }
        // 2. 反序列化并回填 RegisteredClient
        return toAuthorization(data);
    }

    /**
     * 读取存储投影(原始 JSON 形态)。
     * <p>与 {@link #findById} 的差别:不依赖 RegisteredClient 回填,客户端解析失败时
     * 仍能拿到库中事实,索引清理与令牌对比都基于它。</p>
     *
     * @param id 授权 id
     * @return 存储投影;正向键不存在时返回 null
     */
    private AuthorizationData readData(String id) {
        String json = redisUtils.get(String.format(IamRedisKeyConstants.OAUTH2_AUTHORIZATION, id));
        if (json == null) {
            return null;
        }
        return read(json);
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        // 1. 经反向索引定位授权 id
        String id = redisUtils.get(String.format(IamRedisKeyConstants.OAUTH2_AUTHORIZATION_TOKEN, token));
        if (id == null) {
            return null;
        }
        OAuth2Authorization authorization = findById(id);
        if (authorization == null) {
            return null;
        }
        // 2. 授权码只能换一次票:并发换票时两个请求都会读到未失效的码,先抢占成功者继续,
        //    后到者按"码不存在"处理(令牌端点据此返回 invalid_grant)
        if (isAuthorizationCodeType(tokenType) && !claimAuthorizationCode(token, authorization)) {
            return null;
        }
        // 3. 类型为空表示任意类型,直接命中
        if (tokenType == null) {
            return authorization;
        }
        // 4. 校验令牌类型与值
        if (OAuth2TokenType.ACCESS_TOKEN.getValue().equals(tokenType.getValue())) {
            return hasValue(authorization.getAccessToken(), token) ? authorization : null;
        }
        if (OAuth2TokenType.REFRESH_TOKEN.getValue().equals(tokenType.getValue())) {
            return hasValue(authorization.getRefreshToken(), token) ? authorization : null;
        }
        // 5. 其它类型(授权码 / ID 令牌 / state)
        if (hasValue(authorization.getToken(OAuth2AuthorizationCode.class), token)) {
            return authorization;
        }
        if (hasValue(authorization.getToken(OidcIdToken.class), token)) {
            return authorization;
        }
        Object state = authorization.getAttributes().get("state");
        return token.equals(state) ? authorization : null;
    }

    /**
     * 判断请求查找的令牌类型是否为授权码。
     *
     * @param tokenType 令牌类型,可为 null
     * @return true 表示类型为授权码
     */
    private static boolean isAuthorizationCodeType(OAuth2TokenType tokenType) {
        return tokenType != null && OAuth2ParameterNames.CODE.equals(tokenType.getValue());
    }

    /**
     * 抢占授权码的一次性换票资格。
     * <p>以 Redis 原子写入判定归属:写入成功者为本次换票的唯一持有者,标记的存活期与
     * 授权码一致。同一个请求内会两次按码查询授权(PKCE 校验先于换票),因此标记值存的是
     * 请求标识:同一请求放行,其它请求视为码已被换走。</p>
     *
     * @param code          授权码原文
     * @param authorization 该码所属授权
     * @return true 表示本次请求持有该码;false 表示已被其它请求抢先,或授权中不含授权码
     */
    private boolean claimAuthorizationCode(String code, OAuth2Authorization authorization) {
        OAuth2Authorization.Token<OAuth2AuthorizationCode> token =
                authorization.getToken(OAuth2AuthorizationCode.class);
        if (token == null || !code.equals(token.getToken().getTokenValue())) {
            return false;
        }
        String key = String.format(IamRedisKeyConstants.OAUTH2_AUTHORIZATION_CODE_CLAIM, code);
        Duration ttl = ttlOfToken(token.getToken().getExpiresAt());
        String owner = currentRequestOwner();
        if (owner == null) {
            // 无请求上下文(内部调用):没有可复用的请求标识,只认第一次
            return redisUtils.setIfAbsent(key, NO_REQUEST_OWNER, ttl);
        }
        String existing = redisUtils.get(key);
        if (existing != null) {
            return existing.equals(owner);
        }
        return redisUtils.setIfAbsent(key, owner, ttl);
    }

    /**
     * 当前请求的标识,用于判定两次按码查询是否来自同一个请求。
     *
     * @return 请求标识;非 Web 线程返回 null
     */
    private static String currentRequestOwner() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes)) {
            return null;
        }
        Object existing = attributes.getAttribute(CLAIM_OWNER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (existing instanceof String owner) {
            return owner;
        }
        String owner = UUID.randomUUID().toString();
        attributes.setAttribute(CLAIM_OWNER_ATTRIBUTE, owner, RequestAttributes.SCOPE_REQUEST);
        return owner;
    }

    /**
     * 授权对象投影为存储形态:认证主体抽出为独立字段,其余属性原样保留。
     *
     * @param authorization 待投影的授权
     * @return 存储投影
     */
    private AuthorizationData toData(OAuth2Authorization authorization) {
        AuthorizationData data = new AuthorizationData();
        data.setId(authorization.getId());
        data.setRegisteredClientId(authorization.getRegisteredClientId());
        data.setPrincipalName(authorization.getPrincipalName());
        data.setAuthorizationGrantType(authorization.getAuthorizationGrantType().getValue());
        data.setAuthorizedScopes(new ArrayList<>(authorization.getAuthorizedScopes()));
        // 认证主体从属性中抽出为独立字段,只保留本项目的轻量快照;
        // 其余属性(如 state、授权请求)原样保留供授权服务器使用
        Map<String, Object> attributes = new LinkedHashMap<>(authorization.getAttributes());
        Object principalAttribute = attributes.remove(Principal.class.getName());
        if (principalAttribute instanceof Authentication authentication
                && authentication.getPrincipal() instanceof IamUserPrincipal user) {
            data.setPrincipal(user);
        }
        data.setAttributes(attributes);
        OAuth2Authorization.Token<OAuth2AccessToken> access = authorization.getToken(OAuth2AccessToken.class);
        if (access != null) {
            data.setAccessToken(tokenOf(access.getToken().getTokenValue(), access.getMetadata(), null,
                    access.getToken().getIssuedAt(), access.getToken().getExpiresAt()));
            data.getAccessToken().setScopes(access.getToken().getScopes());
        }
        OAuth2Authorization.Token<OAuth2RefreshToken> refresh = authorization.getToken(OAuth2RefreshToken.class);
        if (refresh != null) {
            data.setRefreshToken(tokenOf(refresh.getToken().getTokenValue(), refresh.getMetadata(), null,
                    refresh.getToken().getIssuedAt(), refresh.getToken().getExpiresAt()));
        }
        OAuth2Authorization.Token<OidcIdToken> idToken = authorization.getToken(OidcIdToken.class);
        if (idToken != null) {
            data.setIdToken(tokenOf(idToken.getToken().getTokenValue(), idToken.getMetadata(),
                    idToken.getClaims(), idToken.getToken().getIssuedAt(), idToken.getToken().getExpiresAt()));
        }
        OAuth2Authorization.Token<OAuth2AuthorizationCode> code = authorization.getToken(OAuth2AuthorizationCode.class);
        if (code != null) {
            data.setAuthorizationCode(tokenOf(code.getToken().getTokenValue(), code.getMetadata(), null,
                    code.getToken().getIssuedAt(), code.getToken().getExpiresAt()));
        }
        return data;
    }

    /**
     * 存储投影重建授权对象,回填 RegisteredClient 与令牌元数据。
     *
     * @param data 存储投影
     * @return 授权对象;RegisteredClient 查不到时返回 null
     */
    private OAuth2Authorization toAuthorization(AuthorizationData data) {
        RegisteredClient registeredClient = registeredClientRepository.findById(data.getRegisteredClientId());
        if (registeredClient == null) {
            return null;
        }
        OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(registeredClient)
                .id(data.getId())
                .principalName(data.getPrincipalName())
                .authorizationGrantType(new AuthorizationGrantType(data.getAuthorizationGrantType()))
                .authorizedScopes(new LinkedHashSet<>(data.getAuthorizedScopes()))
                .attributes(attrs -> attrs.putAll(data.getAttributes()));
        // 重新装配认证主体:授权服务器与令牌定制均按 Principal 属性键读取
        IamUserPrincipal principal = data.getPrincipal();
        if (principal != null) {
            builder.attribute(Principal.class.getName(), toAuthentication(principal));
        }
        if (data.getAccessToken() != null) {
            AuthorizationData.TokenData at = data.getAccessToken();
            // scope 必须回填:老投影(未存 scope)按授权范围补齐,OIDC userinfo 依赖它判断 openid
            Set<String> accessScopes = at.getScopes() == null
                    ? new LinkedHashSet<>(data.getAuthorizedScopes()) : at.getScopes();
            builder.token(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                            at.getTokenValue(), at.getIssuedAt(), at.getExpiresAt(), accessScopes),
                    meta -> meta.putAll(at.getMetadata()));
        }
        if (data.getRefreshToken() != null) {
            AuthorizationData.TokenData rt = data.getRefreshToken();
            // refresh token 本身不携带 scope,其权限范围由父授权决定
            builder.token(new OAuth2RefreshToken(rt.getTokenValue(), rt.getIssuedAt(), rt.getExpiresAt()),
                    meta -> meta.putAll(rt.getMetadata()));
        }
        if (data.getIdToken() != null) {
            AuthorizationData.TokenData idt = data.getIdToken();
            builder.token(new OidcIdToken(idt.getTokenValue(), idt.getIssuedAt(), idt.getExpiresAt(),
                    idt.getClaims()), meta -> meta.putAll(idt.getMetadata()));
        }
        if (data.getAuthorizationCode() != null) {
            AuthorizationData.TokenData ac = data.getAuthorizationCode();
            builder.token(new OAuth2AuthorizationCode(ac.getTokenValue(), ac.getIssuedAt(), ac.getExpiresAt()),
                    meta -> meta.putAll(ac.getMetadata()));
        }
        return builder.build();
    }

    /**
     * 由用户快照重建已认证的 Authentication,权限码即 authority。
     *
     * @param principal 用户快照
     * @return 已认证的 Authentication
     */
    private static Authentication toAuthentication(IamUserPrincipal principal) {
        List<GrantedAuthority> authorities = principal.getAuthorities().stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    /**
     * 将令牌字段组装为投影。
     *
     * @param value     令牌值
     * @param metadata  令牌元数据
     * @param claims    ID 令牌声明;非 ID 令牌传 null
     * @param issuedAt  签发时间
     * @param expiresAt 过期时间
     * @return 投影中的令牌字段
     */
    private AuthorizationData.TokenData tokenOf(String value, Map<String, Object> metadata,
                                                Map<String, Object> claims, Instant issuedAt, Instant expiresAt) {
        AuthorizationData.TokenData tokenData = new AuthorizationData.TokenData();
        tokenData.setTokenValue(value);
        tokenData.setMetadata(metadata);
        tokenData.setClaims(claims);
        tokenData.setIssuedAt(issuedAt);
        tokenData.setExpiresAt(expiresAt);
        return tokenData;
    }

    /**
     * 清除一条授权的全部反向索引。
     *
     * @param authorization 待清理的授权
     */
    private void removeIndexKeys(OAuth2Authorization authorization) {
        deleteIndex(authorization.getAccessToken());
        deleteIndex(authorization.getRefreshToken());
        deleteIndex(authorization.getToken(OidcIdToken.class));
        deleteIndex(authorization.getToken(OAuth2AuthorizationCode.class));
        deleteStateIndex(authorization.getAttributes());
    }

    /**
     * 清除一条存储投影的全部反向索引。
     * <p>按投影中的令牌值清理,不依赖 RegisteredClient 回填:客户端被删除或解析失败时
     * 仍要清干净索引,残留键会指向空授权,成为悬空键。</p>
     *
     * @param data 存储投影
     */
    private void removeIndexKeys(AuthorizationData data) {
        deleteTokenIndex(data.getAccessToken() == null ? null : data.getAccessToken().getTokenValue());
        deleteTokenIndex(data.getRefreshToken() == null ? null : data.getRefreshToken().getTokenValue());
        deleteTokenIndex(data.getIdToken() == null ? null : data.getIdToken().getTokenValue());
        deleteTokenIndex(data.getAuthorizationCode() == null ? null : data.getAuthorizationCode().getTokenValue());
        deleteStateIndex(data.getAttributes());
    }

    /**
     * 按令牌值删除反向索引。
     *
     * @param tokenValue 令牌值;为空时不处理
     */
    private void deleteTokenIndex(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            return;
        }
        redisUtils.delete(String.format(IamRedisKeyConstants.OAUTH2_AUTHORIZATION_TOKEN, tokenValue));
    }

    /**
     * 写入 state 索引,供授权同意流程按 state 回查授权。
     *
     * @param attributes 授权属性;state 为空时不写
     * @param id         授权 id
     * @param ttl        索引存活时间
     */
    private void putStateIndex(Map<String, Object> attributes, String id, Duration ttl) {
        if (attributes == null) {
            return;
        }
        Object state = attributes.get(OAuth2ParameterNames.STATE);
        if (state == null || String.valueOf(state).isBlank()) {
            return;
        }
        redisUtils.set(String.format(IamRedisKeyConstants.OAUTH2_AUTHORIZATION_TOKEN,
                String.valueOf(state)), id, ttl);
    }

    /**
     * 删除 state 索引,避免授权删除后残留指向空 id 的键。
     *
     * @param attributes 授权属性;state 为空时不处理
     */
    private void deleteStateIndex(Map<String, Object> attributes) {
        if (attributes == null) {
            return;
        }
        Object state = attributes.get(OAuth2ParameterNames.STATE);
        if (state == null || String.valueOf(state).isBlank()) {
            return;
        }
        redisUtils.delete(String.format(IamRedisKeyConstants.OAUTH2_AUTHORIZATION_TOKEN,
                String.valueOf(state)));
    }

    /**
     * 写入令牌值到授权 id 的反向索引。
     *
     * @param tokenData 投影中的令牌字段;为 null 时不写
     * @param id        授权 id
     */
    private void putIndex(AuthorizationData.TokenData tokenData, String id) {
        if (tokenData == null) {
            return;
        }
        redisUtils.set(String.format(IamRedisKeyConstants.OAUTH2_AUTHORIZATION_TOKEN,
                tokenData.getTokenValue()), id, ttlOfToken(tokenData.getExpiresAt()));
    }

    /**
     * 删除一条令牌的反向索引。
     *
     * @param token 授权中的令牌包装;为 null 时不处理
     */
    private void deleteIndex(OAuth2Authorization.Token<?> token) {
        if (token == null) {
            return;
        }
        redisUtils.delete(String.format(IamRedisKeyConstants.OAUTH2_AUTHORIZATION_TOKEN,
                token.getToken().getTokenValue()));
    }

    /**
     * 判断令牌值是否与给定令牌匹配。
     *
     * @param token 授权中的令牌包装
     * @param value 待匹配的令牌值
     * @return true 表示令牌存在且值相等
     */
    private boolean hasValue(OAuth2Authorization.Token<?> token, String value) {
        return token != null && token.getToken().getTokenValue().equals(value);
    }

    /**
     * 计算授权正向键的存活时间:取各令牌最晚过期者,已过期的按 1 秒处理。
     *
     * @param data 存储投影
     * @return 存活时间;无任何令牌时默认 1 天
     */
    private Duration ttlOf(AuthorizationData data) {
        Instant max = null;
        max = latest(max, data.getAccessToken() == null ? null : data.getAccessToken().getExpiresAt());
        max = latest(max, data.getRefreshToken() == null ? null : data.getRefreshToken().getExpiresAt());
        max = latest(max, data.getIdToken() == null ? null : data.getIdToken().getExpiresAt());
        max = latest(max, data.getAuthorizationCode() == null ? null : data.getAuthorizationCode().getExpiresAt());
        if (max == null) {
            return Duration.ofDays(1);
        }
        Duration ttl = Duration.between(Instant.now(), max);
        return ttl.isNegative() ? Duration.ofSeconds(1) : ttl;
    }

    /**
     * 计算单条令牌相关键的存活时间。
     *
     * @param expiresAt 令牌过期时间
     * @return 距过期的时长;为 null 时默认 1 天,已过期时按 1 秒处理
     */
    private Duration ttlOfToken(Instant expiresAt) {
        if (expiresAt == null) {
            return Duration.ofDays(1);
        }
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        return ttl.isNegative() ? Duration.ofSeconds(1) : ttl;
    }

    /**
     * 返回两个 Instant 中较晚的一个;任一为 null 时返回另一个。
     *
     * @param current   当前值,可为 null
     * @param candidate 候选值,可为 null
     * @return 两者中较晚者
     */
    private Instant latest(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    /**
     * 序列化存储投影为 JSON。
     *
     * @param data 存储投影
     * @return JSON 字符串
     * @throws IllegalStateException 序列化失败
     */
    private String write(AuthorizationData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            // 授权存储的异常会被上层包装后只保留 message,这里必须把根因一并抛出并落日志
            log.error("授权对象序列化失败: id={}", data.getId(), e);
            throw new IllegalStateException("授权对象序列化失败: " + rootMessage(e), e);
        }
    }

    /**
     * 反序列化 JSON 为存储投影。
     *
     * @param json 存储投影 JSON 字符串
     * @return 存储投影
     * @throws IllegalStateException 反序列化失败
     */
    private AuthorizationData read(String json) {
        try {
            return objectMapper.readValue(json, AuthorizationData.class);
        } catch (Exception e) {
            log.error("授权对象反序列化失败", e);
            throw new IllegalStateException("授权对象反序列化失败: " + rootMessage(e), e);
        }
    }

    /**
     * 取异常链最内层的消息,用于在只保留 message 的调用链上定位根因。
     *
     * @param error 外层异常
     * @return 最内层 cause 的 message
     */
    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
