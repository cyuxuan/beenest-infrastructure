package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 组-角色关联实体(iam_group_role)。
 * <p>组内成员继承该角色权限,用于按组织架构批量授权。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class GroupRole extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 组主键 */
    private Long groupId;
    /** 角色主键 */
    private Long roleId;
    /** 授予人用户主键 */
    private Long grantedBy;
}
