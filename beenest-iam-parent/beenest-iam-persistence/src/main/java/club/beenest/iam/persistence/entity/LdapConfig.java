package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 域级 LDAP/AD 用户联合配置实体(iam_realm_ldap_config,每域一行)。
 * <p>本地账号优先,域内按登录名找不到本地用户时才用本配置到目录 bind 认证,
 * 认证通过后按需创建本地用户(JIT)。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class LdapConfig extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 联合开关:关闭时登录流程不访问 LDAP */
    private Boolean enabled;
    /** 目录地址:ldap://host:389 或 ldaps://host:636 */
    private String serverUrl;
    /** 搜索用绑定账号 DN;留空表示匿名绑定(目录允许时) */
    private String bindDn;
    /**
     * bind 口令密文(IamSecretCrypto 信封加密,enc:v1: 前缀)。
     * <p>管理接口只写不读,留空提交表示不修改。</p>
     */
    private String bindPassword;
    /** 用户搜索基 DN */
    private String userBaseDn;
    /** 用户定位过滤器,{0} 为登录名占位(登录名按 RFC4515 转义后填入) */
    private String userFilter;
    /** 稳定外部标识属性(AD:objectGUID;OpenLDAP:entryUUID) */
    private String uuidAttribute;
    /** 登录名属性 */
    private String usernameAttribute;
    /** 邮箱属性 */
    private String emailAttribute;
    /** 显示名属性 */
    private String displayNameAttribute;
    /** 连接超时(毫秒) */
    private Integer connectTimeoutMs;
    /** 读取超时(毫秒) */
    private Integer readTimeoutMs;
}
