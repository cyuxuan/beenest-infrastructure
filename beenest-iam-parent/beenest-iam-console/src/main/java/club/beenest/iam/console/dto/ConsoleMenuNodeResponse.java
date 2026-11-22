package club.beenest.iam.console.dto;

import java.util.List;

/**
 * 应用菜单节点(下发契约)。
 * <p>应用只渲染返回的节点:{@code type=BUTTON} 的节点不参与导航,仅用于按钮级权限判断;
 * {@code permission} 为空表示登录即可见。</p>
 *
 * @param code       节点编码
 * @param name       节点名称
 * @param type       节点类型:MENU 导航 / BUTTON 按钮级权限
 * @param path       前端路由
 * @param icon       图标
 * @param sort       排序号
 * @param permission 关联权限码,为空表示无附加权限
 * @param children   子节点
 */
public record ConsoleMenuNodeResponse(
        String code,
        String name,
        String type,
        String path,
        String icon,
        Integer sort,
        String permission,
        List<ConsoleMenuNodeResponse> children
) {
}
