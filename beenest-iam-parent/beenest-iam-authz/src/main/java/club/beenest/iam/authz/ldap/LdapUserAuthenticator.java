package club.beenest.iam.authz.ldap;

import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.security.IamSecretCrypto;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.entity.LdapConfig;
import club.beenest.iam.persistence.manager.IamLdapConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * LDAP/AD 用户联合认证编排:本地查不到时以目录 bind 认证并按需 JIT 建号。
 * <p>安全口径:</p>
 * <ul>
 *   <li>本地账号优先:域内已存在同名用户时本类完全不参与,LDAP 口令不会覆盖本地口令,
 *       运维自建账号不受目录影响;</li>
 *   <li>对外一律不可区分:目录不可达、账号不存在、口令错误三种情形都返回 empty,由认证
 *       提供者统一按账号或密码错误处理并跑陪跑哈希;真实原因只进审计事件,供运营区分
 *       配置故障与撞库;</li>
 *   <li>JIT 账号复用:首次认证成功后落地本地用户,后续登录走本地链路,
 *       目录仅在该用户被删除后才可能再次 JIT。</li>
 * </ul>
 */
@Component
public class LdapUserAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(LdapUserAuthenticator.class);

    /** 扩展属性中记录目录来源的键 */
    private static final String ATTR_LDAP_DN = "ldap_dn";
    private static final String ATTR_LDAP_UUID = "ldap_uuid";

    private final IamLdapConfigStore configStore;
    private final IamSecretCrypto secretCrypto;
    private final UserProvider userProvider;
    private final LdapGateway ldapGateway;
    private final LoginEventPublisher loginEventPublisher;

    public LdapUserAuthenticator(IamLdapConfigStore configStore, IamSecretCrypto secretCrypto,
                                 UserProvider userProvider, LdapGateway ldapGateway,
                                 LoginEventPublisher loginEventPublisher) {
        this.configStore = configStore;
        this.secretCrypto = secretCrypto;
        this.userProvider = userProvider;
        this.ldapGateway = ldapGateway;
        this.loginEventPublisher = loginEventPublisher;
    }

    /**
     * 尝试以 LDAP 兜底认证。
     *
     * @param realm    当前身份域
     * @param username 登录名(表单原样输入)
     * @param password 口令(表单原样输入,仅用于目录绑定)
     * @return JIT 复用/新建的本地用户;任何失败情形返回 empty(不可区分)
     */
    public Optional<UserModel> authenticate(RealmModel realm, String username, String password) {
        LdapConfig config = configStore.getConfig(realm.id(), realm.realmKey());
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            return Optional.empty();
        }
        LdapSettings settings = LdapSettingsFactory.from(realm.id(), config, secretCrypto);
        if (settings == null) {
            // 必填项(地址/搜索基)缺失或地址协议非法:配置不完整,按未启用处理并留痕
            log.warn("LDAP 配置不完整或目录地址非法: realm={}", realm.realmKey());
            recordFailure(realm, null, username, "LDAP 配置不完整(缺少目录地址或用户搜索基)");
            return Optional.empty();
        }
        try {
            // 1. 以搜索账号定位用户 DN(登录名已按 RFC 4515 转义,防过滤器注入)
            String filter = LdapFilter.build(config.getUserFilter(), username);
            Optional<LdapGateway.LdapEntry> entry = ldapGateway.search(settings, filter);
            if (entry.isEmpty()) {
                recordFailure(realm, null, username, "LDAP 目录中不存在该账号");
                return Optional.empty();
            }
            // 2. 以用户 DN + 输入口令绑定,完成口令校验
            if (!ldapGateway.bind(settings, entry.get().dn(), password)) {
                recordFailure(realm, null, username, "LDAP 口令错误");
                return Optional.empty();
            }
            // 3. 口令已过目录校验:复用或 JIT 创建本地用户
            return provisionUser(realm, username, entry.get(), config);
        } catch (LdapUnavailableException e) {
            // 目录不可达是平台故障:不暴露给用户,但必须在审计里与口令错误分开
            log.warn("LDAP 目录不可用: realm={}, username={}, reason={}",
                    realm.realmKey(), username, e.getMessage());
            recordFailure(realm, null, username, "LDAP 目录不可达");
            return Optional.empty();
        }
    }

    /**
     * 复用已 JIT 的本地账号,或按目录属性创建。
     * <p>先查一次本地:并发 JIT 或用户先被本地手工创建时本地已存在同名账号,直接复用,
     * 不重复建号。</p>
     *
     * @param realm    当前身份域
     * @param username 登录名
     * @param entry    目录中定位到的用户条目
     * @param config   域 LDAP 配置
     * @return JIT 复用或新建的本地用户
     * @throws BusinessException JIT 建号失败(如属性值超长)
     */
    private Optional<UserModel> provisionUser(RealmModel realm, String username,
                                              LdapGateway.LdapEntry entry, LdapConfig config) {
        Optional<UserModel> existing = userProvider.findByUsername(realm.id(), username);
        if (existing.isPresent()) {
            return existing;
        }
        String email = firstAttr(entry, config.getEmailAttribute());
        String nickname = firstAttr(entry, config.getDisplayNameAttribute());
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_LDAP_DN, entry.dn());
        String uuid = firstAttr(entry, config.getUuidAttribute());
        if (uuid != null) {
            attributes.put(ATTR_LDAP_UUID, uuid);
        }
        try {
            UserModel created = userProvider.createFederatedUser(realm.id(), username, email,
                    nickname != null ? nickname : username, null, attributes);
            if (created == null) {
                // 实现契约保证非空;真出现时按失败处理,而不是 Optional.of 抛裸 NPE
                throw new IllegalStateException("createFederatedUser 返回 null,违反 UserProvider 契约");
            }
            log.info("LDAP JIT 建号: realm={}, username={}, dn={}", realm.realmKey(), username, entry.dn());
            return Optional.of(created);
        } catch (DuplicateKeyException e) {
            // 并发 JIT:唯一索引挡住第二个写入,复用先到者
            return userProvider.findByUsername(realm.id(), username);
        } catch (RuntimeException e) {
            // JIT 建号失败(如属性值超长)不能静默转为登录失败,
            // 明确报错并把根因保留在日志,便于运维区分目录故障与配置缺陷
            log.error("LDAP JIT 建号失败: realm={}, username={}", realm.realmKey(), username, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "LDAP 用户初始化失败,请稍后重试");
        }
    }

    /**
     * 取条目的属性首值。
     *
     * @param entry     目录条目
     * @param attribute 属性名
     * @return 首值;属性名为空或条目无该属性时返回 null
     */
    private static String firstAttr(LdapGateway.LdapEntry entry, String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return null;
        }
        return entry.first(attribute);
    }

    /**
     * LDAP 失败留痕:真实原因只进审计事件,不回响应。
     *
     * @param realm    当前身份域
     * @param userId   用户主键,未知时传 null
     * @param username 登录名
     * @param reason   审计中记录的失败原因
     */
    private void recordFailure(RealmModel realm, Long userId, String username, String reason) {
        loginEventPublisher.publish(realm.realmKey(), userId, username,
                LoginEventEvent.LOGIN_FAILURE, "PASSWORD", null, null, reason);
    }
}
