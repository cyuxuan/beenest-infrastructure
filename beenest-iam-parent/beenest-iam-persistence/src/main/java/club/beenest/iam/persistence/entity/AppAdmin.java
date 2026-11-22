package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 应用管理员实体(iam_app_admin)。
 * <p>主体为 USER 或 GROUP,组管理员按组成员生效;管理员可维护本应用的菜单与授权。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AppAdmin extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 应用服务主键 */
    private Long appServiceId;
    /** 主体类型:USER/GROUP */
    private String subjectType;
    /** 主体主键 */
    private Long subjectId;
    /** 授予人用户主键 */
    private Long grantedBy;
}
