package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.AppWorkspaceResponse;
import club.beenest.iam.admin.dto.MappingSourceResponse;
import club.beenest.iam.admin.dto.MenuCreateRequest;
import club.beenest.iam.admin.dto.MenuResponse;
import club.beenest.iam.admin.dto.MenuUpdateRequest;
import club.beenest.iam.admin.dto.PermissionCreateRequest;
import club.beenest.iam.admin.dto.PermissionResponse;
import club.beenest.iam.admin.dto.PermissionUpdateRequest;
import club.beenest.iam.admin.dto.RoleCreateRequest;
import club.beenest.iam.admin.dto.RoleResponse;
import club.beenest.iam.admin.dto.RoleUpdateRequest;

import java.util.List;

/**
 * 应用管理工作台:应用管理员在自身管理范围内维护菜单、角色与权限。
 * <p>所有读写都以路径中的应用为主键做归属校验,请求体中的归属字段不可信。</p>
 */
public interface AppWorkspaceService {

    /**
     * 查询应用工作台概览,含菜单、角色、权限、角色映射与管理员数量。
     *
     * @param appId 应用服务主键
     * @return 工作台概览
     * @throws BusinessException 应用不存在或不属于当前域
     */
    AppWorkspaceResponse overview(Long appId);

    /**
     * 查询应用下的全部菜单。
     *
     * @param appId 应用服务主键
     * @return 菜单列表
     * @throws BusinessException 应用不存在或不属于当前域
     */
    List<MenuResponse> listMenus(Long appId);

    /**
     * 在应用下新建菜单。
     * <p>菜单归属以路径中的应用为准,请求体中的归属字段不被采信。</p>
     *
     * @param appId   应用服务主键
     * @param request 菜单创建参数
     * @return 新建后的菜单
     * @throws BusinessException 应用不存在或不属于当前域
     */
    MenuResponse createMenu(Long appId, MenuCreateRequest request);

    /**
     * 更新应用下的菜单。
     *
     * @param appId   应用服务主键
     * @param menuId  菜单主键
     * @param request 菜单更新参数
     * @return 更新后的菜单
     * @throws BusinessException 应用或菜单不存在,或菜单不属于该应用
     */
    MenuResponse updateMenu(Long appId, Long menuId, MenuUpdateRequest request);

    /**
     * 删除应用下的菜单。
     *
     * @param appId  应用服务主键
     * @param menuId 菜单主键
     * @throws BusinessException 应用或菜单不存在,或菜单不属于该应用
     */
    void deleteMenu(Long appId, Long menuId);

    /**
     * 查询应用下的全部角色。
     *
     * @param appId 应用服务主键
     * @return 角色列表
     * @throws BusinessException 应用不存在或不属于当前域
     */
    List<RoleResponse> listRoles(Long appId);

    /**
     * 在应用下新建角色。
     * <p>工作台只创建 APP 容器角色,域角色与客户端角色由全局管理员维护。</p>
     *
     * @param appId   应用服务主键
     * @param request 角色创建参数
     * @return 新建后的角色
     * @throws BusinessException 应用不存在或不属于当前域
     */
    RoleResponse createRole(Long appId, RoleCreateRequest request);

    /**
     * 更新应用下的角色。
     *
     * @param appId   应用服务主键
     * @param roleId  角色主键
     * @param request 角色更新参数
     * @return 更新后的角色
     * @throws BusinessException 应用或角色不存在,或角色不属于该应用
     */
    RoleResponse updateRole(Long appId, Long roleId, RoleUpdateRequest request);

    /**
     * 删除应用下的角色。
     *
     * @param appId  应用服务主键
     * @param roleId 角色主键
     * @throws BusinessException 应用或角色不存在,或角色不属于该应用
     */
    void deleteRole(Long appId, Long roleId);

    /**
     * 查询角色已分配的权限。
     *
     * @param appId  应用服务主键
     * @param roleId 角色主键
     * @return 权限列表
     * @throws BusinessException 应用或角色不存在,或角色不属于该应用
     */
    List<PermissionResponse> listRolePermissions(Long appId, Long roleId);

    /**
     * 覆盖式分配角色权限。
     * <p>保存后失效受影响用户的权限缓存。</p>
     *
     * @param appId         应用服务主键
     * @param roleId        角色主键
     * @param permissionIds 权限主键集合
     * @return 分配后的权限列表
     * @throws BusinessException 应用或角色不存在,或角色不属于该应用
     */
    List<PermissionResponse> assignRolePermissions(Long appId, Long roleId, List<Long> permissionIds);

    /**
     * 查询角色已分配的菜单。
     *
     * @param appId  应用服务主键
     * @param roleId 角色主键
     * @return 菜单列表
     * @throws BusinessException 应用或角色不存在,或角色不属于该应用
     */
    List<MenuResponse> listRoleMenus(Long appId, Long roleId);

    /**
     * 覆盖式分配角色菜单。
     *
     * @param appId   应用服务主键
     * @param roleId  角色主键
     * @param menuIds 菜单主键集合
     * @return 分配后的菜单列表
     * @throws BusinessException 应用或角色不存在,或角色不属于该应用
     */
    List<MenuResponse> assignRoleMenus(Long appId, Long roleId, List<Long> menuIds);

    /**
     * 查询应用权限命名空间下的全部权限。
     *
     * @param appId 应用服务主键
     * @return 权限列表
     * @throws BusinessException 应用不存在或不属于当前域
     */
    List<PermissionResponse> listPermissions(Long appId);

    /**
     * 在应用权限命名空间下新建权限。
     * <p>工作台只能创建应用权限,平台权限归属以路径中的应用为准。</p>
     *
     * @param appId   应用服务主键
     * @param request 权限创建参数
     * @return 新建后的权限
     * @throws BusinessException 应用不存在或不属于当前域
     */
    PermissionResponse createPermission(Long appId, PermissionCreateRequest request);

    /**
     * 更新应用下的权限。
     *
     * @param appId         应用服务主键
     * @param permissionId  权限主键
     * @param request       权限更新参数
     * @return 更新后的权限
     * @throws BusinessException 应用或权限不存在,或权限不属于该应用
     */
    PermissionResponse updatePermission(Long appId, Long permissionId, PermissionUpdateRequest request);

    /**
     * 删除应用下的权限。
     *
     * @param appId        应用服务主键
     * @param permissionId 权限主键
     * @throws BusinessException 应用或权限不存在,或权限不属于该应用
     */
    void deletePermission(Long appId, Long permissionId);

    /**
     * 查询可映射到应用角色的来源对象,供角色映射下拉使用。
     *
     * @param appId      应用服务主键
     * @param sourceType 来源类型:GROUP 用户组 / REALM_ROLE 域角色
     * @param keyword    编码或名称检索词,为空时返回全部
     * @return 来源对象列表
     * @throws BusinessException 应用不存在、不属于当前域,或来源类型非法
     */
    List<MappingSourceResponse> listMappingSources(Long appId, String sourceType, String keyword);
}
