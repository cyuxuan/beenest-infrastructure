package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 应用角色映射实体(iam_app_role_mapping)。
 * <p>MAPPED 应用将 IAM 的组/域角色映射为应用自有角色码,令牌只输出映射结果。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AppRoleMapping extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 应用服务主键 */
    private Long appServiceId;
    /** 来源类型:GROUP/REALM_ROLE */
    private String sourceType;
    /** 来源主体主键(组主键或域角色主键) */
    private Long sourceId;
    /** 目标应用角色码 */
    private String targetRoleCode;
    /** 是否启用 */
    private Boolean enabled;
}
