package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 身份提供方属性映射器实体(iam_identity_provider_mapper)。
 * <p>将外部 IdP 声明映射为本地用户属性。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class IdentityProviderMapper extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 身份提供方主键 */
    private Long idpId;
    /** 映射器类型(如 ATTRIBUTE) */
    private String mapperType;
    /** 外部属性名 */
    private String sourceAttribute;
    /** 本地用户属性键 */
    private String targetAttribute;
}
