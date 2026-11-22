package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 身份提供方实体(iam_identity_provider)。
 * <p>记录外部 IdP 的类型(providerId)、域内唯一别名与 JSON 配置。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class IdentityProvider extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 域内唯一别名 */
    private String alias;
    /** 提供方类型:oidc/wechat/alipay/feishu */
    private String providerId;
    /** 展示名 */
    private String displayName;
    /** 是否启用 */
    private Boolean enabled;
    /** 提供方配置(JSONB,字段随 providerId 而定) */
    private String config;
}
