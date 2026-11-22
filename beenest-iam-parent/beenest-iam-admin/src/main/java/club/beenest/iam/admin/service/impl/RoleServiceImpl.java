package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.RoleDtoConverter;
import club.beenest.iam.admin.dto.PermissionResponse;
import club.beenest.iam.admin.dto.MenuResponse;
import club.beenest.iam.admin.dto.RoleCreateRequest;
import club.beenest.iam.admin.dto.RoleResponse;
import club.beenest.iam.admin.dto.RoleUpdateRequest;
import club.beenest.iam.admin.service.RoleService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Client;
import club.beenest.iam.persistence.entity.Menu;
import club.beenest.iam.persistence.entity.Permission;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamClientManager;
import club.beenest.iam.persistence.manager.IamMenuManager;
import club.beenest.iam.persistence.manager.IamPermissionManager;
import club.beenest.iam.persistence.manager.IamRoleManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 角色管理服务实现。
 * <p>角色操作限定在当前身份域;应用角色与已绑定应用的 CLIENT 角色只能授予本应用的权限与菜单;
 * 系统内置角色不允许删除。归属(容器类型与所属应用/客户端)决定授权面,只在角色尚未承载
 * 任何授权关系时允许变更。</p>
 */
@Service
public class RoleServiceImpl implements RoleService {

    /** 容器类型:域级角色 */
    private static final String CONTAINER_REALM = "REALM";
    /** 容器类型:应用角色 */
    private static final String CONTAINER_APP = "APP";
    /** 容器类型:客户端角色(兼容存量) */
    private static final String CONTAINER_CLIENT = "CLIENT";
    /** 应用授权主体类型:角色 */
    private static final String GRANT_SUBJECT_ROLE = "ROLE";

    private final IamRoleManager roleManager;
    private final IamPermissionManager permissionManager;
    private final IamMenuManager menuManager;
    private final IamApplicationServiceManager appServiceManager;
    private final IamClientManager clientManager;
    private final RoleDtoConverter dtoConverter;

    public RoleServiceImpl(IamRoleManager roleManager, IamPermissionManager permissionManager,
                           IamMenuManager menuManager, IamApplicationServiceManager appServiceManager,
                           IamClientManager clientManager, RoleDtoConverter dtoConverter) {
        this.roleManager = roleManager;
        this.permissionManager = permissionManager;
        this.menuManager = menuManager;
        this.appServiceManager = appServiceManager;
        this.clientManager = clientManager;
        this.dtoConverter = dtoConverter;
    }

    @Override
    public PageResult<RoleResponse> page(String roleCode, String roleName, String containerType, Long clientId,
                                         Long appServiceId, int pageNum, int pageSize) {
        // 1. 组装单表查询条件(clientId 用于兼容客户端角色,appServiceId 用于筛选应用角色)
        Role condition = new Role();
        condition.setRoleCode(roleCode);
        condition.setRoleName(roleName);
        condition.setContainerType(containerType);
        condition.setClientId(clientId);
        condition.setAppServiceId(appServiceId);
        // 2. 分页查询当前域角色后逐条转换
        PageResult<Role> page = roleManager.page(currentRealmId(), condition, pageNum, pageSize);
        return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(), page.getPages(),
                page.getRecords().stream().map(dtoConverter::toResponse).toList());
    }

    @Override
    public RoleResponse get(Long id) {
        return dtoConverter.toResponse(requireRoleInRealm(id));
    }

    @Override
    public RoleResponse create(RoleCreateRequest request) {
        Long realmId = currentRealmId();
        String containerType = resolveContainerType(request);
        Long clientId = null;
        Long appServiceId = null;
        // 1. 容器归属归一化:REALM 不挂应用/客户端,APP 必须挂应用,CLIENT 兼容客户端
        if (CONTAINER_APP.equals(containerType)) {
            appServiceId = request.appServiceId();
            if (appServiceId == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "应用角色必须指定所属应用");
            }
            requireAppInRealm(appServiceId);
            // 同一应用下若已有同编码的历史 CLIENT 角色,拒绝再建 APP 角色,避免双份角色
            if (roleManager.getByAppAndCode(realmId, appServiceId, request.roleCode()) != null) {
                throw new BusinessException(ErrorCode.ALREADY_EXISTS, "应用内角色编码已存在: " + request.roleCode());
            }
        } else if (CONTAINER_CLIENT.equals(containerType)) {
            clientId = request.clientId();
            if (clientId == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "客户端角色必须指定所属客户端");
            }
            Client client = clientManager.getById(clientId);
            if (client == null || !realmId.equals(client.getRealmId())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "客户端不存在于当前身份域: " + clientId);
            }
            // 历史客户端角色若已绑定应用,则按应用角色参与作用域校验
            ApplicationService boundApp = appServiceManager.getBySsoClientPk(realmId, clientId);
            appServiceId = boundApp == null ? null : boundApp.getId();
            if (roleManager.getByScope(realmId, containerType, clientId, appServiceId,
                    request.roleCode()) != null) {
                throw new BusinessException(ErrorCode.ALREADY_EXISTS, "角色编码已存在: " + request.roleCode());
            }
        } else if (roleManager.getByScope(realmId, containerType, null, null,
                request.roleCode()) != null) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "角色编码已存在: " + request.roleCode());
        }
        // 2. 落库并回读完整字段
        Role role = dtoConverter.toEntity(request, realmId);
        role.setContainerType(containerType);
        role.setClientId(clientId);
        role.setAppServiceId(appServiceId);
        roleManager.create(role);
        return dtoConverter.toResponse(roleManager.getById(role.getId()));
    }

    @Override
    @Transactional
    public RoleResponse update(Long id, RoleUpdateRequest request) {
        // 1. 目标角色必须在当前域
        Role role = requireRoleInRealm(id);
        // 2. 归属变更单独处理:容器类型决定授权面,只允许在角色尚未承载授权关系时调整
        applyContainerChange(role, request);
        // 3. 仅更新显式给出的字段
        dtoConverter.applyUpdate(role, request);
        roleManager.update(role);
        return dtoConverter.toResponse(roleManager.getById(id));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        // 1. 目标角色必须在当前域
        Role role = requireRoleInRealm(id);
        // 2. 系统内置角色由平台维护,删除会破坏初始化数据的可预期性
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "系统内置角色不允许删除");
        }
        roleManager.remove(id);
        // 3. 该角色的应用授权行在同一事务内逻辑删除:残留行会被授权弹窗回读,
        //    整表覆盖保存时按"主体不存在"拒绝
        appServiceManager.removeGrantsBySubject(GRANT_SUBJECT_ROLE, id);
    }

    @Override
    public List<PermissionResponse> listPermissions(Long id) {
        // 角色必须先通过域校验,避免跨域读取他人授权
        requireRoleInRealm(id);
        return permissionManager.listByRoleId(id).stream()
                .map(RoleServiceImpl::toPermissionResponse)
                .toList();
    }

    @Override
    @Transactional
    public List<PermissionResponse> assignPermissions(Long id, List<Long> permissionIds) {
        // 1. 角色必须存在且属于当前域
        Role role = requireRoleInRealm(id);
        Long realmId = currentRealmId();
        // 2. 去重后校验:权限必须存在且属于本域;应用角色只能授予本应用的权限
        List<Long> distinctIds = permissionIds == null ? List.of()
                : permissionIds.stream().filter(Objects::nonNull).distinct().toList();
        if (!distinctIds.isEmpty()) {
            List<Permission> permissions = permissionManager.listByIds(distinctIds);
            boolean allInScope = permissions.size() == distinctIds.size()
                    && permissions.stream().allMatch(p ->
                    realmId.equals(p.getRealmId()) && permissionInRoleScope(role, p));
            if (!allInScope) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "存在不属于当前身份域或该角色作用域的权限");
            }
        }
        // 3. 整体覆盖直接权限(菜单派生权限不受影响)
        permissionManager.replaceRolePermissions(realmId, role.getId(), distinctIds);
        return permissionManager.listByRoleId(id).stream()
                .map(RoleServiceImpl::toPermissionResponse)
                .toList();
    }

    @Override
    public List<MenuResponse> listMenus(Long id) {
        // 角色必须先通过域校验,避免跨域读取他人授权
        requireRoleInRealm(id);
        return menuManager.listByRoleId(id).stream()
                .map(RoleServiceImpl::toMenuResponse)
                .toList();
    }

    @Override
    @Transactional
    public List<MenuResponse> assignMenus(Long id, List<Long> menuIds) {
        Role role = requireRoleInRealm(id);
        Long realmId = currentRealmId();
        // 1. 去重后校验:菜单必须存在且属于本域;应用角色只能授予本应用的菜单
        List<Long> distinctIds = menuIds == null ? List.of()
                : menuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (!distinctIds.isEmpty()) {
            var menus = menuManager.listByIds(distinctIds);
            boolean allInScope = menus.size() == distinctIds.size()
                    && menus.stream().allMatch(m ->
                    realmId.equals(m.getRealmId()) && menuInRoleScope(role, m));
            if (!allInScope) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "存在不属于当前身份域或该角色作用域的菜单");
            }
        }
        // 2. 整体覆盖菜单授予:授予菜单即派生菜单上挂载的权限
        menuManager.replaceRoleMenus(realmId, role.getId(), distinctIds);
        return menuManager.listByRoleId(id).stream()
                .map(RoleServiceImpl::toMenuResponse)
                .toList();
    }

    /**
     * 菜单实体转响应,权限编码置空,角色侧只关心授予关系。
     *
     * @param menu 菜单
     * @return 菜单响应
     */
    private static MenuResponse toMenuResponse(Menu menu) {
        return new MenuResponse(
                menu.getId(),
                menu.getRealmId(),
                menu.getAppServiceId(),
                menu.getParentId(),
                menu.getMenuCode(),
                menu.getMenuName(),
                menu.getMenuType(),
                menu.getPath(),
                menu.getIcon(),
                menu.getPermissionId(),
                null,
                menu.getSortOrder(),
                menu.getEnabled(),
                menu.getCreatedTime(),
                menu.getUpdatedTime());
    }

    /**
     * 权限实体转响应。
     *
     * @param permission 权限
     * @return 权限响应
     */
    private static PermissionResponse toPermissionResponse(Permission permission) {
        return new PermissionResponse(
                permission.getId(),
                permission.getRealmId(),
                permission.getAppServiceId(),
                permission.getPermissionCode(),
                permission.getPermissionName(),
                permission.getResource(),
                permission.getAction(),
                permission.getDescription(),
                permission.getCreatedTime(),
                permission.getUpdatedTime());
    }

    /**
     * 解析请求中的目标归属。
     * <p>请求未涉及归属时返回 null。只对显式给出的维度做解析,未给出的维度沿用库中值——
     * 否则"改个名字"也会被当成归属变更,进而被使用条数校验挡住。</p>
     *
     * @param role    库中角色
     * @param request 更新请求
     * @return 目标归属;请求未涉及归属时为 null
     * @throws BusinessException 目标归属缺少必填项,或所属应用/客户端不存在于当前域
     */
    private RoleContainer resolveTargetContainer(Role role, RoleUpdateRequest request) {
        if (request.containerType() == null && request.appServiceId() == null && request.clientId() == null) {
            return null;
        }
        String containerType = request.containerType() == null || request.containerType().isBlank()
                ? role.getContainerType()
                : request.containerType().trim().toUpperCase();
        Long realmId = currentRealmId();
        Long clientId = null;
        Long appServiceId = null;
        if (CONTAINER_APP.equals(containerType)) {
            appServiceId = request.appServiceId() != null ? request.appServiceId() : role.getAppServiceId();
            if (appServiceId == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "应用角色必须指定所属应用");
            }
            requireAppInRealm(appServiceId);
        } else if (CONTAINER_CLIENT.equals(containerType)) {
            clientId = request.clientId() != null ? request.clientId() : role.getClientId();
            if (clientId == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "客户端角色必须指定所属客户端");
            }
            Client client = clientManager.getById(clientId);
            if (client == null || !realmId.equals(client.getRealmId())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "客户端不存在于当前身份域: " + clientId);
            }
            // 换了客户端才重新按绑定关系推导所属应用;沿用原客户端时保留库中值,
            // 避免"客户端后来绑了应用"被误判成归属变更
            if (request.clientId() != null) {
                ApplicationService boundApp = appServiceManager.getBySsoClientPk(realmId, clientId);
                appServiceId = boundApp == null ? null : boundApp.getId();
            } else {
                appServiceId = role.getAppServiceId();
            }
        }
        return new RoleContainer(containerType, clientId, appServiceId);
    }

    /**
     * 按请求变更角色归属。
     * <p>归属决定授权面:有效角色解析不按容器过滤,改归属会让角色的权限范围对所有持有者
     * 同时改变。因此只在角色尚未承载任何授权关系时允许调整,否则要求新建角色并迁移。</p>
     *
     * @param role    库中角色,允许被就地修改
     * @param request 更新请求
     * @throws BusinessException 系统内置角色、角色已承载授权关系,或目标作用域下角色编码冲突
     */
    private void applyContainerChange(Role role, RoleUpdateRequest request) {
        RoleContainer target = resolveTargetContainer(role, request);
        if (target == null || target.sameAs(role)) {
            return;
        }
        // 1. 系统内置角色由平台维护,归属是初始化数据的一部分
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "系统内置角色不允许变更归属");
        }
        // 2. 已承载授权关系的角色不允许移动:移动会让这些授权在新作用域下静默失效或越界
        long usage = roleManager.countUsage(role.getId());
        if (usage > 0) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "该角色已承载 " + usage + " 条授权关系(用户/组/岗位分配、权限、菜单或应用授权),"
                            + "归属不可变更;请新建目标归属的角色并把授权迁过去,再删除本角色");
        }
        // 3. 角色编码在目标作用域内必须唯一
        Role conflict = roleManager.getByScope(role.getRealmId(), target.containerType(),
                target.clientId(), target.appServiceId(), role.getRoleCode());
        if (conflict != null && !Objects.equals(conflict.getId(), role.getId())) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS,
                    "目标归属下角色编码已存在: " + role.getRoleCode());
        }
        // 4. 落库并同步内存态,后续 update 只写名称与启用状态
        roleManager.changeContainer(role, target.containerType(), target.clientId(), target.appServiceId());
        role.setContainerType(target.containerType());
        role.setClientId(target.clientId());
        role.setAppServiceId(target.appServiceId());
    }

    /**
     * 角色归属(容器类型 + 所属客户端 + 所属应用)。
     *
     * @param containerType 容器类型
     * @param clientId      所属客户端,非 CLIENT 容器为 null
     * @param appServiceId  所属应用,REALM 容器为 null
     */
    private record RoleContainer(String containerType, Long clientId, Long appServiceId) {

        /**
         * 目标归属与库中归属是否一致。
         *
         * @param role 库中角色
         * @return 三要素完全一致时为 true
         */
        boolean sameAs(Role role) {
            return containerType.equals(role.getContainerType())
                    && Objects.equals(clientId, role.getClientId())
                    && Objects.equals(appServiceId, role.getAppServiceId());
        }
    }

    /**
     * 解析请求的容器类型,缺省按域级角色处理。
     *
     * @param request 角色创建参数
     * @return 归一化后的容器类型
     * @throws BusinessException 类型不在 REALM、APP、CLIENT 之内
     */
    private String resolveContainerType(RoleCreateRequest request) {
        String type = request.containerType() == null || request.containerType().isBlank()
                ? CONTAINER_REALM : request.containerType().trim().toUpperCase();
        if (!CONTAINER_REALM.equals(type) && !CONTAINER_APP.equals(type) && !CONTAINER_CLIENT.equals(type)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "容器类型只能是 REALM、APP 或 CLIENT");
        }
        return type;
    }

    /**
     * 角色是否受应用作用域限制。
     * <p>APP 角色与已绑定应用的 CLIENT 角色只能操作本应用资源;
     * REALM 角色与未绑定应用的存量 CLIENT 角色保持跨应用能力。</p>
     *
     * @param role 角色
     * @return 受应用作用域限制时为 true
     */
    private static boolean appScoped(Role role) {
        return CONTAINER_APP.equals(role.getContainerType())
                || (CONTAINER_CLIENT.equals(role.getContainerType()) && role.getAppServiceId() != null);
    }

    /**
     * 判断权限是否在角色的授权作用域内。
     *
     * @param role       角色
     * @param permission 权限
     * @return 角色不受应用限制或权限属于该应用时为 true
     */
    private static boolean permissionInRoleScope(Role role, Permission permission) {
        return !appScoped(role) || role.getAppServiceId().equals(permission.getAppServiceId());
    }

    /**
     * 判断菜单是否在角色的授权作用域内。
     *
     * @param role 角色
     * @param menu 菜单
     * @return 角色不受应用限制或菜单属于该应用时为 true
     */
    private static boolean menuInRoleScope(Role role, Menu menu) {
        return !appScoped(role) || role.getAppServiceId().equals(menu.getAppServiceId());
    }

    /**
     * 当前调用者的身份域主键。
     *
     * @return 身份域主键
     */
    private Long currentRealmId() {
        return CurrentUserHolder.require().getRealmId();
    }

    /**
     * 读取属于当前域的角色,不存在或跨域一律按不存在处理。
     *
     * @param id 角色主键
     * @return 角色
     * @throws BusinessException 角色不存在或不属于当前域
     */
    private Role requireRoleInRealm(Long id) {
        Role role = roleManager.getById(id);
        if (role == null || !currentRealmId().equals(role.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在: " + id);
        }
        return role;
    }

    /**
     * 读取属于当前域的应用服务。
     *
     * @param id 应用服务主键
     * @return 应用服务
     * @throws BusinessException 应用不存在或不属于当前域
     */
    private ApplicationService requireAppInRealm(Long id) {
        ApplicationService app = appServiceManager.getById(id);
        if (app == null || !currentRealmId().equals(app.getRealmId())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "应用服务不存在: " + id);
        }
        return app;
    }
}
