package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 用户级特殊授权/撤销实体(iam_user_permission)。
 * <p>GRANT 在角色派生结果之上追加,REVOKE 从中剔除;expiredTime 到期后自动失效。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class UserPermission extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 用户主键 */
    private Long userId;
    /** 权限主键 */
    private Long permissionId;
    /** 授权类型:GRANT 临时授予 / REVOKE 临时撤销 */
    private String grantType;
    /** 原因说明 */
    private String reason;
    /** 过期时间(到期自动失效) */
    private LocalDateTime expiredTime;
    /** 最近一次设置该授权的操作人用户主键 */
    private Long grantedBy;
}
