package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 成员任职关系实体(iam_user_group)。
 * <p>记录成员所属组、担任的岗位与是否主部门;岗位携带的角色参与授权计算。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class UserGroup extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 用户主键 */
    private Long userId;
    /** 组主键 */
    private Long groupId;
    /** 在该组担任的岗位(可为空:只归属不任职) */
    private Long positionId;
    /** 是否主部门;一人至多一个主部门,其余为兼职 */
    private Boolean isPrimary;
}
