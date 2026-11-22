package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.MenuResponse;
import club.beenest.iam.admin.dto.PermissionResponse;
import club.beenest.iam.admin.dto.RoleCreateRequest;
import club.beenest.iam.admin.dto.RoleResponse;
import club.beenest.iam.admin.dto.RoleUpdateRequest;
import club.beenest.iam.common.result.PageResult;

import java.util.List;

/**
 * 角色管理服务。
 * <p>与用户一致,角色操作限定在调用者所属身份域内。角色分域级、应用、客户端三类容器,
 * 应用角色只能授予本应用的权限与菜单。</p>
 */
public interface RoleService {

    /**
     * 分页查询当前域角色。
     *
     * @param roleCode      角色编码模糊匹配,可空
     * @param roleName      角色名称模糊匹配,可空
     * @param containerType 容器类型精确匹配,可空;为空时返回全部容器类型
     * @param clientId      客户端主键,查询历史 CLIENT 角色时用,可空
     * @param appServiceId  应用主键,查询应用角色时用,可空
     * @param pageNum       页码,从 1 开始
     * @param pageSize      每页条数
     * @return 角色分页结果
     */
    PageResult<RoleResponse> page(String roleCode, String roleName, String containerType, Long clientId,
                                  Long appServiceId, int pageNum, int pageSize);

    /**
     * 查询单个角色。
     *
     * @param id 角色主键
     * @return 角色详情
     * @throws BusinessException 角色不存在或不属于当前域
     */
    RoleResponse get(Long id);

    /**
     * 新建角色。
     * <p>容器类型限 REALM/APP/CLIENT:REALM 不挂应用与客户端,APP 必须挂本域应用,
     * CLIENT 必须挂本域客户端;角色编码在同作用域唯一。</p>
     *
     * @param request 角色创建参数
     * @return 新建后的角色
     * @throws BusinessException 容器类型非法、归属缺失或编码重复
     */
    RoleResponse create(RoleCreateRequest request);

    /**
     * 更新角色,仅覆盖请求中显式给出的字段。
     * <p>归属(容器类型与所属应用/客户端)只在角色尚未承载任何授权关系时允许变更;
     * 已承载分配或授予的角色会拒绝变更并说明原因。</p>
     *
     * @param id      角色主键
     * @param request 角色更新参数
     * @return 更新后的角色
     * @throws BusinessException 角色不存在、不属于当前域、归属变更被拒绝或目标作用域下角色编码冲突
     */
    RoleResponse update(Long id, RoleUpdateRequest request);

    /**
     * 逻辑删除角色。
     * <p>系统内置角色由平台维护,不允许删除。</p>
     *
     * @param id 角色主键
     * @throws BusinessException 角色不存在、不属于当前域或为系统内置角色
     */
    void delete(Long id);

    /**
     * 查询角色已直接授予的权限。
     * <p>只返回角色与权限的直接关联;菜单派生权限由菜单授予产生,不在此列。</p>
     *
     * @param id 角色主键
     * @return 直接权限列表
     * @throws BusinessException 角色不存在或不属于当前域
     */
    List<PermissionResponse> listPermissions(Long id);

    /**
     * 整体覆盖角色的直接权限,菜单派生权限不受影响。
     * <p>权限必须属于当前域;应用角色只能授予本应用的权限;
     * 变更在账号下一次认证时生效,已登录会话持有登录时刻的权限快照。</p>
     *
     * @param id            角色主键
     * @param permissionIds 目标权限主键集合;空集合表示清空直接权限
     * @return 覆盖后的直接权限列表
     * @throws BusinessException 角色不存在或存在越权的权限
     */
    List<PermissionResponse> assignPermissions(Long id, List<Long> permissionIds);

    /**
     * 查询角色已授予的菜单。
     * <p>菜单授予会派生菜单上挂载的权限,与直接权限是两条独立的授权路径。</p>
     *
     * @param id 角色主键
     * @return 已授予的菜单列表
     * @throws BusinessException 角色不存在或不属于当前域
     */
    List<MenuResponse> listMenus(Long id);

    /**
     * 整体覆盖角色的菜单集合,授予菜单即派生菜单上挂载的权限。
     * <p>菜单必须属于当前域;应用角色只能授予本应用的菜单;
     * 变更在账号下一次认证时生效。</p>
     *
     * @param id      角色主键
     * @param menuIds 目标菜单主键集合;空集合表示清空菜单授予
     * @return 覆盖后的菜单列表
     * @throws BusinessException 角色不存在或存在越权的菜单
     */
    List<MenuResponse> assignMenus(Long id, List<Long> menuIds);
}
