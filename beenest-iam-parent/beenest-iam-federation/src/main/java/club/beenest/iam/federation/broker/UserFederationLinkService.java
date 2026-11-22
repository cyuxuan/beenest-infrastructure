package club.beenest.iam.federation.broker;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.IdpMapperModel;
import club.beenest.iam.core.model.RoleModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.core.spi.provider.RoleProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 外部身份绑定服务:三方回调身份 → 本地用户(find-or-create)。
 * <p>已绑定则直接复用本地用户;未绑定则创建外部用户并写入绑定关系。</p>
 * <p>创建与复用时都会执行提供方侧的属性映射规则:外部属性搬进本地扩展属性,
 * 外部角色换成本地角色授权。</p>
 */
@Component
public class UserFederationLinkService {

    private static final Logger log = LoggerFactory.getLogger(UserFederationLinkService.class);

    /** 登录名最大长度(iam_user.username 为 VARCHAR(100)) */
    private static final int USERNAME_MAX = 100;
    /** 外部标识清洗后保留的最大长度 */
    private static final int EXTERNAL_ID_MAX = 60;
    /** 摘要后缀长度:保证清洗/截断后仍能区分不同外部标识 */
    private static final int HASH_SUFFIX_LENGTH = 8;
    /** 登录名中允许的字符(其余一律替换为下划线) */
    private static final String ALLOWED_CHARS = "[^A-Za-z0-9._-]";

    private final UserProvider userProvider;
    private final IdentityProviderStore identityProviderStore;
    private final RoleProvider roleProvider;
    private final IdpMappingService mappingService;

    public UserFederationLinkService(UserProvider userProvider,
                                     IdentityProviderStore identityProviderStore,
                                     RoleProvider roleProvider,
                                     IdpMappingService mappingService) {
        this.userProvider = userProvider;
        this.identityProviderStore = identityProviderStore;
        this.roleProvider = roleProvider;
        this.mappingService = mappingService;
    }

    /**
     * 按外部身份查找或创建本地用户。
     *
     * @param identity 经回调换回的外部身份
     * @param realmId  所属身份域
     * @param idpId    身份提供方主键(iam_identity_provider.id)
     * @return 本地用户
     */
    public UserModel findOrCreateLinkedUser(BrokeredIdentityContext identity, Long realmId, Long idpId) {
        // 1. 已有绑定关系则直接复用本地用户(绑定行存在但用户已被删除的极端情况走后续匹配)
        Optional<Long> linked = identityProviderStore.findLinkedUserId(idpId, identity.getExternalId());
        if (linked.isPresent()) {
            Optional<UserModel> existing = userProvider.findById(linked.get());
            if (existing.isPresent()) {
                // 老账号先建号后补手机号:本地手机号为空时补写,已有值保持不动
                UserModel user = fillVerifiedPhone(existing.get(), identity.getVerifiedPhone());
                // 已存在的账号也要补跑角色映射:管理员后加的映射规则对老账号同样生效
                applyRoleMapping(identity, realmId, idpId, user.id());
                return user;
            }
        }
        // 2. 绑定缺失时按提供方担保的手机号归并:后台导入或其它渠道建的账号直接接管
        String verifiedPhone = trimToNull(identity.getVerifiedPhone());
        if (verifiedPhone != null) {
            Optional<UserModel> byPhone = userProvider.findByPhone(realmId, verifiedPhone);
            if (byPhone.isPresent()) {
                UserModel user = byPhone.get();
                log.info("外部身份按已验证手机号归并到既有账号: userId={}, idpId={}", user.id(), idpId);
                linkIdentity(identity, realmId, idpId, user);
                applyRoleMapping(identity, realmId, idpId, user.id());
                return user;
            }
        }
        // 3. 首次登录:登录名按 别名-外部标识 生成,域内唯一且可追溯到来源提供方
        String username = buildUsername(identity.getAlias(), identity.getExternalId());
        // 4. 执行属性映射规则,决定扩展属性与要授予的角色
        IdpMappingService.MappingResult mapping = mappingService.apply(
                identity, identityProviderStore.listMappers(idpId));
        UserModel user;
        try {
            user = userProvider.createFederatedUser(realmId, username, identity.getEmail(),
                    identity.getNickname(), verifiedPhone, mapping.attributes());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发回调下唯一索引冲突:对方已建绑定则复用其结果
            Optional<Long> racing = identityProviderStore.findLinkedUserId(idpId, identity.getExternalId());
            if (racing.isPresent()) {
                return userProvider.findById(racing.get()).orElseThrow(() -> e);
            }
            // 同一手机号并发建号:后到者归并到先到者,不再报登录名冲突
            if (verifiedPhone != null) {
                Optional<UserModel> byPhone = userProvider.findByPhone(realmId, verifiedPhone);
                if (byPhone.isPresent()) {
                    linkIdentity(identity, realmId, idpId, byPhone.get());
                    return byPhone.get();
                }
            }
            // 登录名与已有账号冲突:该外部身份无法登录,提示先处理本地同名账号
            throw new BusinessException(ErrorCode.ALREADY_EXISTS,
                    "外部身份对应的本地登录名已存在:" + username
                            + ";请先在用户管理中改名,或解绑该账号后再试");
        } catch (RuntimeException e) {
            // 其余运行期异常:并发情况下对方可能已建好绑定,在则不重复抛错
            Optional<Long> racing = identityProviderStore.findLinkedUserId(idpId, identity.getExternalId());
            if (racing.isPresent()) {
                return userProvider.findById(racing.get()).orElseThrow(() -> e);
            }
            throw e;
        }
        // 5. 写入绑定关系
        identityProviderStore.link(realmId, user.id(), idpId, identity.getExternalId(), identity.getUsername());
        // 6. 授予映射规则命中的本地角色
        grantRoles(mapping.roleCodes(), realmId, user.id());
        return user;
    }

    /**
     * 给绑定命中的老账号补写已验证手机号。
     * <p>只在本地手机号为空时写入:已有值可能是用户当前使用的号码,
     * 直接覆盖会与 (域, 已验证手机号) 唯一约束或其它账号冲突。</p>
     *
     * @param user          本地账号快照
     * @param verifiedPhone 提供方担保的手机号
     * @return 补写后的账号快照;无需补写时返回原快照
     */
    private UserModel fillVerifiedPhone(UserModel user, String verifiedPhone) {
        String phone = trimToNull(verifiedPhone);
        if (phone == null) {
            return user;
        }
        if (user.phone() != null && !user.phone().isBlank()) {
            if (!phone.equals(user.phone())) {
                log.info("外部身份手机号与本地账号不一致,保留本地值: userId={}", user.id());
            }
            return user;
        }
        userProvider.updateVerifiedPhone(user.id(), phone);
        return userProvider.findById(user.id()).orElse(user);
    }

    /**
     * 把外部身份绑定到已确定的本地账号。
     * <p>(域, 提供方, 外部标识) 与 (账号, 提供方) 各有唯一索引:并发绑定同一身份按成功处理,
     * 同一账号已绑定该提供方其它外部身份时拒绝,避免悄悄顶掉既有绑定。</p>
     *
     * @param identity 外部身份上下文
     * @param realmId  所属身份域
     * @param idpId    身份提供方主键
     * @param user     本地账号
     * @throws BusinessException 外部身份或本地账号已有其它绑定
     */
    private void linkIdentity(BrokeredIdentityContext identity, Long realmId, Long idpId, UserModel user) {
        try {
            identityProviderStore.link(realmId, user.id(), idpId,
                    identity.getExternalId(), identity.getUsername());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            Optional<Long> bound = identityProviderStore.findLinkedUserId(idpId, identity.getExternalId());
            if (bound.isPresent() && bound.get().equals(user.id())) {
                return;
            }
            if (bound.isPresent()) {
                throw new BusinessException(ErrorCode.ALREADY_EXISTS,
                        "该外部身份已绑定其它账号,请先在用户管理中解绑");
            }
            throw new BusinessException(ErrorCode.ALREADY_EXISTS,
                    "该手机号账号已绑定同一提供方的其它外部身份,请先在用户管理中解绑");
        }
    }

    /**
     * 空白字符串归一为 null。
     *
     * @param value 原值,可为 null
     * @return 去空白后的值;入参为空或空白时返回 null
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 生成本地登录名:{别名}-{外部标识}。
     * <p>外部标识来自第三方,长度与字符不受控:统一清洗为字母数字与
     * {@code . _ -}(其余替换为下划线),超长时截断并补外部标识的摘要后缀,
     * 保证结果不超过列宽,且不同外部标识截断后不会撞成同一个登录名。</p>
     *
     * @param alias      提供方别名
     * @param externalId 外部唯一标识
     * @return 合法且不超过 100 字符的登录名
     */
    static String buildUsername(String alias, String externalId) {
        String prefix = sanitize(alias);
        String raw = externalId == null ? "" : externalId;
        String cleaned = sanitize(raw);
        boolean lossy = !cleaned.equals(raw) || cleaned.length() > EXTERNAL_ID_MAX;
        if (cleaned.length() > EXTERNAL_ID_MAX) {
            cleaned = cleaned.substring(0, EXTERNAL_ID_MAX);
        }
        if (cleaned.isEmpty()) {
            cleaned = "u";
        }
        String body = prefix.isEmpty() ? cleaned : prefix + "-" + cleaned;
        // 截断或清洗都可能撞名,补一段外部标识摘要保证可区分
        if (lossy) {
            String suffix = shortHash(raw);
            body = body.length() + suffix.length() + 1 > USERNAME_MAX
                    ? body.substring(0, USERNAME_MAX - suffix.length() - 1) + "-" + suffix
                    : body + "-" + suffix;
        }
        if (body.length() > USERNAME_MAX) {
            body = body.substring(0, USERNAME_MAX);
        }
        return body;
    }

    /**
     * 非法字符替换为下划线。
     *
     * @param value 原值,可为 null
     * @return 清洗后的值;入参为 null 时返回空串
     */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll(ALLOWED_CHARS, "_");
    }

    /**
     * 外部标识摘要(SHA-256 前 4 字节的十六进制,共 8 位),
     * 用于区分清洗/截断后的同名情况。
     *
     * @param value 外部标识原值
     * @return 8 位十六进制摘要
     * @throws IllegalStateException JDK 不支持 SHA-256 时(规范要求必选,实际不可达)
     */
    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 4 && i < bytes.length; i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.substring(0, Math.min(HASH_SUFFIX_LENGTH, builder.length()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("摘要算法不可用", e);
        }
    }

    /**
     * 已存在账号补跑角色映射,使管理员后加的映射规则对老账号同样生效。
     *
     * @param identity 外部身份
     * @param realmId  身份域主键
     * @param idpId    提供方主键
     * @param userId   本地用户主键
     */
    private void applyRoleMapping(BrokeredIdentityContext identity, Long realmId, Long idpId, Long userId) {
        List<IdpMapperModel> mappers = identityProviderStore.listMappers(idpId);
        if (mappers == null || mappers.isEmpty()) {
            return;
        }
        IdpMappingService.MappingResult mapping = mappingService.apply(identity, mappers);
        grantRoles(mapping.roleCodes(), realmId, userId);
    }

    /**
     * 角色编码 → 本地角色主键 → 授予;匹配不上的编码只记日志,不阻断登录。
     *
     * @param roleCodes 映射命中的角色编码
     * @param realmId   身份域主键
     * @param userId    本地用户主键
     */
    private void grantRoles(List<String> roleCodes, Long realmId, Long userId) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return;
        }
        List<Long> roleIds = new ArrayList<>();
        for (String roleCode : roleCodes) {
            Optional<RoleModel> role = roleProvider.findByCode(realmId, roleCode);
            if (role.isPresent()) {
                roleIds.add(role.get().id());
            } else {
                log.warn("外部身份映射命中了不存在的本地角色,已跳过: roleCode={}, realmId={}", roleCode, realmId);
            }
        }
        if (!roleIds.isEmpty()) {
            roleProvider.grantRoles(realmId, userId, roleIds);
        }
    }
}
