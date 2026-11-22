package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 域签名密钥实体(iam_realm_key)。
 * <p>密钥轮换时,新密钥行 active,旧密钥保留供验签。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class RealmKey extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 密钥类型 */
    private String keyType;
    /** 私钥(加密存储) */
    private String privateKey;
    /** 公钥(PEM/JWKS 用) */
    private String publicKey;
    /** 是否当前生效 */
    private Boolean active;
    /** 生效时间 */
    private LocalDateTime activatedTime;
    /** 退役时间 */
    private LocalDateTime retiredTime;
}
