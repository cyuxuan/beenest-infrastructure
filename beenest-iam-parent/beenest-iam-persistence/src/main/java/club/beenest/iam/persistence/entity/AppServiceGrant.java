package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 应用服务授权实体(iam_app_service_grant)。
 * <p>应用 visibility 为 ROLE 时,按本表判定可见主体。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AppServiceGrant extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 应用服务主键 */
    private Long appServiceId;
    /** 主体类型:USER/ROLE/GROUP */
    private String subjectType;
    /** 主体主键 */
    private Long subjectId;
}
