package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 菜单实体(iam_menu)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Menu extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 所属应用服务(为空表示平台级公共菜单,不参与应用菜单下发) */
    private Long appServiceId;
    /** 父菜单(为空表示根) */
    private Long parentId;
    /** 菜单编码 */
    private String menuCode;
    /** 菜单名称 */
    private String menuName;
    /** 菜单类型:MENU 页面/目录 / BUTTON 按钮 */
    private String menuType;
    /** 前端路由路径 */
    private String path;
    /** 图标 */
    private String icon;
    /** 关联权限(挂载菜单即随角色派生该权限) */
    private Long permissionId;
    /** 排序号 */
    private Integer sortOrder;
    /** 是否启用 */
    private Boolean enabled;
}
