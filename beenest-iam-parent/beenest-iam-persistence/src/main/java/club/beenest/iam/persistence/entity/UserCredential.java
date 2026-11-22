package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 用户凭证实体(iam_user_credential)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class UserCredential extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 所属用户 */
    private Long userId;
    /** 凭证类型:PASSWORD/OTP/WEBAUTHN/RECOVERY_CODE */
    private String credentialType;
    /** 凭证内容(密码为哈希,TOTP 为密钥种子,恢复码为哈希列表) */
    private String secretData;
    /** 盐值 */
    private String salt;
    /** 加密算法 */
    private String algorithm;
    /** 迭代次数 */
    private Integer iterations;
    /** 是否启用 */
    private Boolean enabled;
    /** 过期时间 */
    private LocalDateTime expiredTime;
}
