package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 用户-角色关联实体(iam_user_role)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class UserRole extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 用户主键 */
    private Long userId;
    /** 角色主键 */
    private Long roleId;
    /** 授予人用户主键 */
    private Long grantedBy;
}
