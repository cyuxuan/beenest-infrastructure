package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 岗位-角色关联实体(iam_position_role)。
 * <p>岗位默认角色,任职该岗位的用户继承这些角色,与组角色同等参与授权计算。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class PositionRole extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 岗位主键 */
    private Long positionId;
    /** 角色主键 */
    private Long roleId;
    /** 授予人用户主键 */
    private Long grantedBy;
}
