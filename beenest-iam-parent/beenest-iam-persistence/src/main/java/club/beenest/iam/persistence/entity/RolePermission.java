package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 角色-权限直接关联实体(iam_role_permission)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class RolePermission extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 角色主键 */
    private Long roleId;
    /** 权限主键 */
    private Long permissionId;
}
