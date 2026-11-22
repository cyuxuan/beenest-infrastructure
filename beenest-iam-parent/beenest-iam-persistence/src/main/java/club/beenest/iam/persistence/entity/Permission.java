package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 权限实体(iam_permission)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Permission extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 所属应用服务(为空表示平台级权限) */
    private Long appServiceId;
    /** 权限编码(resource:action) */
    private String permissionCode;
    /** 权限名称 */
    private String permissionName;
    /** 资源 */
    private String resource;
    /** 动作 */
    private String action;
    /** 说明 */
    private String description;
}
