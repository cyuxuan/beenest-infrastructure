package club.beenest.iam.authz.saml;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.RoleModel;
import club.beenest.iam.core.model.SamlServiceProviderModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.RoleProvider;
import club.beenest.iam.core.spi.provider.SamlServiceProviderStore;
import club.beenest.iam.core.spi.provider.UserProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SAML 身份提供方编排:把协议层的能力接到本地账号与登记数据上。
 * <p>协议报文怎么编解码由 {@link SamlIdpProtocol} 负责,本类只决定
 * "这个请求该不该签发、断言里放谁的信息"。</p>
 */
@Component
public class SamlIdpService {

    /** 断言有效期:与协议层保持一致,防重放窗口按它设定 */
    private static final Duration ASSERTION_VALIDITY = Duration.ofMinutes(5);

    private final RealmProvider realmProvider;
    private final SamlServiceProviderStore serviceProviderStore;
    private final SamlIdpSigningService signingService;
    private final SamlIdpAttributeMapper attributeMapper;
    private final UserProvider userProvider;
    private final RoleProvider roleProvider;
    private final RedisUtils redisUtils;

    public SamlIdpService(RealmProvider realmProvider,
                          SamlServiceProviderStore serviceProviderStore,
                          SamlIdpSigningService signingService,
                          SamlIdpAttributeMapper attributeMapper,
                          UserProvider userProvider,
                          RoleProvider roleProvider,
                          RedisUtils redisUtils) {
        this.realmProvider = realmProvider;
        this.serviceProviderStore = serviceProviderStore;
        this.signingService = signingService;
        this.attributeMapper = attributeMapper;
        this.userProvider = userProvider;
        this.roleProvider = roleProvider;
        this.redisUtils = redisUtils;
    }

    // ------------------------------------------------------------------ 登记与端点

    /**
     * 身份域必须存在且启用。
     *
     * @param realmKey 身份域标识
     * @return 身份域
     * @throws BusinessException 域不存在或已停用
     */
    public RealmModel requireRealm(String realmKey) {
        RealmModel realm = realmProvider.findByRealmKey(realmKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "身份域不存在: " + realmKey));
        if (!realm.enabled()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "身份域已停用: " + realmKey);
        }
        return realm;
    }

    /**
     * 按 SP 实体 ID 取登记项。
     *
     * @param realmId  所属身份域
     * @param entityId SP 实体 ID
     * @return 登记项
     * @throws BusinessException 未登记或已停用
     */
    public SamlServiceProviderModel requireServiceProvider(Long realmId, String entityId) {
        SamlServiceProviderModel provider = serviceProviderStore.findByEntityId(realmId, entityId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "该应用未登记为 SAML 服务方: " + entityId));
        if (!provider.enabled()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "该 SAML 服务方已停用: " + entityId);
        }
        return provider;
    }

    /**
     * 生成 IdP 元数据。
     *
     * @param realmId   身份域主键
     * @param realmKey  身份域标识
     * @param endpoints 端点集合
     * @return 元数据 XML
     */
    public String metadata(Long realmId, String realmKey, SamlIdpEndpoints endpoints) {
        SamlSigningMaterial material = signingService.material(realmId, realmKey);
        return SamlIdpProtocol.buildMetadata(endpoints.entityId(), endpoints.ssoUrl(), endpoints.sloUrl(),
                material.certificate());
    }

    // ------------------------------------------------------------------ 断言

    /**
     * 组装待签发的断言内容。
     *
     * @param realm      身份域
     * @param provider   SP 登记项
     * @param request    AuthnRequest 解析结果;为空表示 IdP 发起
     * @param principal  当前登录主体
     * @param sessionId  当前会话标识,作为 SessionIndex
     * @param idpEntityId IdP 实体 ID
     * @return 断言内容
     * @throws BusinessException 用户不存在
     */
    public SamlIdpAssertionSpec assertionSpec(RealmModel realm,
                                              SamlServiceProviderModel provider,
                                              SamlIdpAuthnRequest request,
                                              IamUserPrincipal principal,
                                              String sessionId,
                                              String idpEntityId) {
        UserModel user = userProvider.findById(principal.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        List<String> roleCodes = roleProvider.findRolesByUserId(realm.id(), user.id()).stream()
                .map(RoleModel::roleCode)
                .toList();
        // NameID 用登录名:它在域内唯一且稳定,改昵称或换邮箱都不影响对端已建立的账号关联
        String nameIdFormat = isPresent(request == null ? null : request.nameIdFormat())
                ? request.nameIdFormat() : provider.nameIdFormat();
        String acsUrl = isPresent(request == null ? null : request.assertionConsumerServiceUrl())
                ? request.assertionConsumerServiceUrl() : provider.acsUrl();
        return new SamlIdpAssertionSpec(
                idpEntityId,
                provider.entityId(),
                acsUrl,
                request == null ? null : request.id(),
                user.username(),
                nameIdFormat,
                sessionId,
                Instant.now().plus(ASSERTION_VALIDITY),
                attributeMapper.map(provider, user, roleCodes, realm.realmKey()),
                provider.signAssertion());
    }

    /**
     * 签发 SAMLResponse。
     *
     * @param realmId 身份域主键
     * @param realmKey 身份域标识
     * @param spec    断言内容
     * @return 响应 XML
     */
    public String buildResponse(Long realmId, String realmKey, SamlIdpAssertionSpec spec) {
        return SamlIdpProtocol.buildResponse(spec, signingService.material(realmId, realmKey));
    }

    // ------------------------------------------------------------------ 防重放

    /**
     * 标记 AuthnRequest 已消费。
     * <p>同一个请求 ID 只允许签发一次断言;窗口取断言有效期,过期后的重放本身
     * 也会因为断言时间戳不合法而被 SP 拒绝。</p>
     *
     * @param realmKey  身份域标识
     * @param requestId 请求 ID
     * @throws BusinessException 该请求已被消费
     */
    public void consumeRequestId(String realmKey, String requestId) {
        String key = String.format(IamRedisKeyConstants.SAML_CONSUMED_REQUEST, realmKey, requestId);
        if (!redisUtils.setIfAbsent(key, "1", ASSERTION_VALIDITY)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "该登录请求已被使用,请重新从应用发起");
        }
    }

    // ------------------------------------------------------------------ 登出

    /**
     * 按断言主体标识反查本地用户。
     * <p>SAML 的单点登出语义是"退出 IdP 会话",因此调用方拿到用户主键后
     * 结束其全部会话,而不是只退某一个应用。</p>
     *
     * @param realmId 身份域主键
     * @param nameId  断言主体标识(登录名)
     * @return 本地用户主键;NameID 为空或用户不存在时为空
     */
    public Optional<Long> findUserIdByNameId(Long realmId, String nameId) {
        if (nameId == null || nameId.isBlank()) {
            return Optional.empty();
        }
        return userProvider.findByUsername(realmId, nameId).map(UserModel::id);
    }

    /**
     * 判断字符串非空。
     *
     * @param value 待判定字符串
     * @return 非 null 且含非空白字符时为 true
     */
    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
