package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.MenuCreateRequest;
import club.beenest.iam.admin.dto.MenuResponse;
import club.beenest.iam.admin.dto.MenuUpdateRequest;
import club.beenest.iam.admin.service.MenuService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Menu;
import club.beenest.iam.persistence.entity.Permission;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamMenuManager;
import club.beenest.iam.persistence.manager.IamPermissionManager;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 菜单管理实现。
 * <p>校验应用与菜单属于当前域、父子菜单同属一个应用、菜单编码在应用内唯一;
 * 菜单挂载权限后,授予菜单即派生该权限,删除菜单时同步清理角色-菜单关联。</p>
 */
@Service
public class MenuServiceImpl implements MenuService {

    /** 菜单类型:页面/目录 */
    private static final String TYPE_MENU = "MENU";

    private final IamMenuManager menuManager;
    private final IamApplicationServiceManager appServiceManager;
    private final IamPermissionManager permissionManager;

    public MenuServiceImpl(IamMenuManager menuManager, IamApplicationServiceManager appServiceManager,
                           IamPermissionManager permissionManager) {
        this.menuManager = menuManager;
        this.appServiceManager = appServiceManager;
        this.permissionManager = permissionManager;
    }

    @Override
    public List<MenuResponse> listByApp(Long appServiceId) {
        Long realmId = currentRealmId();
        ApplicationService app = requireAppInRealm(appServiceId);
        return menuManager.listByAppServiceId(realmId, app.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public MenuResponse get(Long id) {
        return toResponse(requireMenuInRealm(id));
    }

    @Override
    public MenuResponse create(MenuCreateRequest request) {
        Long realmId = currentRealmId();
        ApplicationService app = requireAppInRealm(request.appServiceId());
        // 1. 父菜单必须同属该应用,避免菜单树跨应用串接
        if (request.parentId() != null) {
            Menu parent = menuManager.getById(request.parentId());
            if (parent == null || !realmId.equals(parent.getRealmId())
                    || !app.getId().equals(parent.getAppServiceId())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "父菜单不存在或不属于该应用");
            }
        }
        // 2. 菜单编码在应用内唯一
        boolean duplicated = menuManager.listByAppServiceId(realmId, app.getId()).stream()
                .anyMatch(menu -> menu.getMenuCode().equalsIgnoreCase(request.menuCode()));
        if (duplicated) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "菜单编码已存在: " + request.menuCode());
        }
        // 3. 挂载的权限必须属于本域且与本应用同域(平台菜单只能绑平台权限)
        if (request.permissionId() != null) {
            requirePermissionForApp(request.permissionId(), app.getId());
        }
        Menu menu = new Menu();
        menu.setRealmId(realmId);
        menu.setAppServiceId(app.getId());
        menu.setParentId(request.parentId());
        menu.setMenuCode(request.menuCode());
        menu.setMenuName(request.menuName());
        menu.setMenuType(request.menuType() == null || request.menuType().isBlank()
                ? TYPE_MENU : request.menuType().toUpperCase());
        menu.setPath(request.path());
        menu.setIcon(request.icon());
        menu.setPermissionId(request.permissionId());
        menu.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        menu.setEnabled(request.enabled() == null || request.enabled());
        menuManager.create(menu);
        return toResponse(menuManager.getById(menu.getId()));
    }

    @Override
    public MenuResponse update(Long id, MenuUpdateRequest request) {
        Menu menu = requireMenuInRealm(id);
        if (request.menuName() != null) {
            menu.setMenuName(request.menuName());
        }
        if (request.menuType() != null) {
            menu.setMenuType(request.menuType().toUpperCase());
        }
        if (request.path() != null) {
            menu.setPath(request.path());
        }
        if (request.icon() != null) {
            menu.setIcon(request.icon());
        }
        if (request.permissionId() != null) {
            requirePermissionForApp(request.permissionId(), menu.getAppServiceId());
            menu.setPermissionId(request.permissionId());
        }
        if (request.sortOrder() != null) {
            menu.setSortOrder(request.sortOrder());
        }
        if (request.enabled() != null) {
            menu.setEnabled(request.enabled());
        }
        menuManager.update(menu);
        return toResponse(menuManager.getById(id));
    }

    @Override
    public void delete(Long id) {
        Menu menu = requireMenuInRealm(id);
        menuManager.removeWithChildren(menu.getRealmId(), id);
    }

    /**
     * 菜单实体转响应,补齐权限编码。
     *
     * @param menu 菜单
     * @return 菜单响应
     */
    private MenuResponse toResponse(Menu menu) {
        Permission permission = menu.getPermissionId() == null
                ? null : permissionManager.getById(menu.getPermissionId());
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
                permission == null ? null : permission.getPermissionCode(),
                menu.getSortOrder(),
                menu.getEnabled(),
                menu.getCreatedTime(),
                menu.getUpdatedTime());
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
     * 读取属于当前域的应用服务。
     *
     * @param id 应用服务主键
     * @return 应用服务
     * @throws BusinessException 应用不存在或不属于当前域
     */
    private ApplicationService requireAppInRealm(Long id) {
        ApplicationService app = id == null ? null : appServiceManager.getById(id);
        if (app == null || !currentRealmId().equals(app.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "应用服务不存在: " + id);
        }
        return app;
    }

    /**
     * 读取属于当前域的菜单。
     *
     * @param id 菜单主键
     * @return 菜单
     * @throws BusinessException 菜单不存在或不属于当前域
     */
    private Menu requireMenuInRealm(Long id) {
        Menu menu = menuManager.getById(id);
        if (menu == null || !currentRealmId().equals(menu.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "菜单不存在: " + id);
        }
        return menu;
    }

    /**
     * 读取属于当前域的权限。
     *
     * @param id 权限主键
     * @return 权限
     * @throws BusinessException 权限不存在或不属于当前域
     */
    private Permission requirePermissionInRealm(Long id) {
        Permission permission = permissionManager.getById(id);
        if (permission == null || !currentRealmId().equals(permission.getRealmId())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "权限不存在于当前身份域: " + id);
        }
        return permission;
    }

    /**
     * 读取属于当前域且与菜单同应用的权限。
     *
     * @param id           权限主键
     * @param appServiceId 应用服务主键
     * @return 权限
     * @throws BusinessException 权限不存在、不属于当前域或不属于该应用
     */
    private Permission requirePermissionForApp(Long id, Long appServiceId) {
        Permission permission = requirePermissionInRealm(id);
        if (!Objects.equals(permission.getAppServiceId(), appServiceId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "权限不属于该应用: " + id);
        }
        return permission;
    }
}
