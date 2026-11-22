package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 域级发信配置实体(iam_realm_mail_config,每域一行)。
 * <p>配置完整且启用时,本域邮件经该 SMTP 发出;否则回落到全局 {@code iam.mail.*} 配置。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class RealmMailConfig extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 本域发信开关:关闭时一律走全局配置 */
    private Boolean enabled;
    /** SMTP 主机 */
    private String host;
    /** SMTP 端口 */
    private Integer port;
    /** SMTP 用户名;留空表示不认证 */
    private String username;
    /**
     * SMTP 口令密文(IamSecretCrypto 信封加密,enc:v1: 前缀)。
     * <p>管理接口只写不读,留空提交表示不修改。</p>
     */
    private String password;
    /** 发件邮箱 */
    private String fromAddress;
    /** 发件人展示名 */
    private String fromName;
    /** 是否启用 STARTTLS */
    private Boolean starttls;
    /** 是否启用 SSL(465 端口直连 TLS 时用) */
    private Boolean ssl;
    /** 连接/读取/写入超时(毫秒) */
    private Integer timeoutMillis;
}
