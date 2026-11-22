package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 角色实体(iam_role)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Role extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 角色编码 */
    private String roleCode;
    /** 角色名称 */
    private String roleName;
    /** 容器类型:REALM 域角色 / APP 应用角色 / CLIENT 历史客户端角色 */
    private String containerType;
    /** CLIENT 历史类型所属客户端(其余类型为 null) */
    private Long clientId;
    /** 所属应用服务(APP 角色必填,CLIENT 历史角色按绑定客户端回填) */
    private Long appServiceId;
    /** 是否系统内置(不可删除) */
    private Boolean isSystem;
    /** 是否启用 */
    private Boolean isActive;
}
