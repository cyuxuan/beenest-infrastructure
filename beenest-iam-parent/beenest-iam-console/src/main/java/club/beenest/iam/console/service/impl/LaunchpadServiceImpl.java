package club.beenest.iam.console.service.impl;

import club.beenest.iam.authz.authorization.ApplicationVisibilityService;
import club.beenest.iam.authz.launch.LaunchPlan;
import club.beenest.iam.authz.launch.LaunchPlanner;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.console.dto.ConsoleLauncherAppResponse;
import club.beenest.iam.console.dto.ConsoleMenuNodeResponse;
import club.beenest.iam.console.dto.ConsoleMenuTreeResponse;
import club.beenest.iam.console.service.LaunchpadService;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Menu;
import club.beenest.iam.persistence.manager.IamEffectiveRoleResolver;
import club.beenest.iam.persistence.manager.IamMenuManager;
import club.beenest.iam.persistence.manager.IamPermissionManager;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 应用门户服务实现。
 * <p>可见性取全员可见、按角色/用户/组授权、按权限码三类规则的并集,判定口径与
 * 授权端点一致由 {@link ApplicationVisibilityService} 提供。
 * 菜单下发按托管模式处理:IAM/HYBRID 返回已授予且启用中的菜单树,APP 模式返回空树。</p>
 * <p>启动只做规划、不发凭据:门户把浏览器送到应用入口地址,由应用自己发起授权流程。
 * 判定集中在 {@link LaunchPlanner},卡片状态与跳转目标取自同一份计划。</p>
 */
@Service
public class LaunchpadServiceImpl implements LaunchpadService {

    /** 菜单托管模式:应用自管 */
    private static final String MENU_MODE_APP = "APP";
    /** 菜单托管模式缺省:一级模块由 IAM 管 */
    private static final String MENU_MODE_DEFAULT = "HYBRID";

    private final ApplicationVisibilityService visibilityService;
    private final IamEffectiveRoleResolver effectiveRoleResolver;
    private final IamMenuManager menuManager;
    private final IamPermissionManager permissionManager;
    private final LaunchPlanner launchPlanner;

    public LaunchpadServiceImpl(ApplicationVisibilityService visibilityService,
                                IamEffectiveRoleResolver effectiveRoleResolver,
                                IamMenuManager menuManager, IamPermissionManager permissionManager,
                                LaunchPlanner launchPlanner) {
        this.visibilityService = visibilityService;
        this.effectiveRoleResolver = effectiveRoleResolver;
        this.menuManager = menuManager;
        this.permissionManager = permissionManager;
        this.launchPlanner = launchPlanner;
    }

    @Override
    public List<ConsoleLauncherAppResponse> listApps() {
        IamUserPrincipal principal = CurrentUserHolder.require();
        return visibilityService.visibleApps(principal).stream()
                .map(app -> toResponse(app, launchPlanner.plan(app)))
                .toList();
    }

    @Override
    public ConsoleMenuTreeResponse menus(String appCode) {
        IamUserPrincipal principal = CurrentUserHolder.require();
        // 1. 应用须可见,与启动接口同一道校验
        ApplicationService app = requireVisibleApp(principal, appCode);
        String mode = normalizeMenuMode(app.getMenuMode());
        // 2. 应用自管菜单:只告诉模式,由应用按权限码渲染
        if (MENU_MODE_APP.equals(mode)) {
            return new ConsoleMenuTreeResponse(app.getAppCode(), mode, version(List.of()), List.of());
        }
        // 3. 取用户在本应用下被授予的菜单(有效角色:直授 + 组继承)
        List<Long> roleIds = effectiveRoleResolver.resolveRoleIds(
                principal.getRealmId(), principal.getUserId());
        List<Menu> granted = menuManager.listGranted(principal.getRealmId(), roleIds, app.getId());
        // 4. 父节点未授予时子节点不单独下挂,避免出现无父的悬浮菜单
        Set<Long> grantedIds = granted.stream().map(Menu::getId).collect(Collectors.toSet());
        List<Menu> effective = granted.stream()
                .filter(menu -> menu.getParentId() == null || grantedIds.contains(menu.getParentId()))
                .toList();
        return new ConsoleMenuTreeResponse(app.getAppCode(), mode, version(effective),
                buildTree(effective, permissionCodes(effective)));
    }

    @Override
    public LaunchPlan planLaunch(String appCode) {
        IamUserPrincipal principal = CurrentUserHolder.require();
        // 不可见与不存在给同一个回答,不区分——区分了就等于告诉调用方"这个编码存在"
        return visibilityService.visibleApps(principal).stream()
                .filter(item -> item.getAppCode().equals(appCode))
                .findFirst()
                .map(launchPlanner::plan)
                .orElseGet(() -> LaunchPlanner.blocked(appCode, "应用不存在或未授权"));
    }

    /**
     * 取对当前用户可见的应用。
     * <p>菜单下发同样受列表权限约束,不能靠"知道应用编码"绕过可见性。</p>
     *
     * @param principal 当前登录用户
     * @param appCode   应用编码
     * @return 应用服务
     * @throws BusinessException 应用不存在或对当前用户不可见
     */
    private ApplicationService requireVisibleApp(IamUserPrincipal principal, String appCode) {
        return visibilityService.visibleApps(principal).stream()
                .filter(item -> item.getAppCode().equals(appCode))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "应用不存在或未授权: " + appCode));
    }

    /**
     * 应用转卡片视图。
     *
     * @param app  应用
     * @param plan 启动计划,提供可进入判定与不可进入的原因
     * @return 卡片视图
     */
    private static ConsoleLauncherAppResponse toResponse(ApplicationService app, LaunchPlan plan) {
        return new ConsoleLauncherAppResponse(
                app.getAppCode(),
                app.getAppName(),
                app.getAppType(),
                app.getSsoMode(),
                app.getIconUrl(),
                app.getCategory(),
                app.getSortOrder(),
                app.getDescription(),
                normalizeMenuMode(app.getMenuMode()),
                !plan.blocked(),
                plan.hint());
    }

    /**
     * 空白判定。
     *
     * @param value 待判定值
     * @return 为 null 或全空白时返回 true
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 菜单托管模式归一化,缺省为 HYBRID。
     *
     * @param menuMode 原始模式值
     * @return 去空格并转大写的模式值
     */
    private static String normalizeMenuMode(String menuMode) {
        return isBlank(menuMode) ? MENU_MODE_DEFAULT : menuMode.trim().toUpperCase();
    }

    /**
     * 菜单版本:取最大更新时间,供应用侧缓存失效。
     *
     * @param menus 菜单列表
     * @return 最大更新时间的文本表示;无更新时间时为空串
     */
    private String version(List<Menu> menus) {
        return menus.stream()
                .map(Menu::getUpdatedTime)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(LocalDateTime::toString)
                .orElse("");
    }

    /**
     * 菜单挂载的权限主键转权限码。
     *
     * @param menus 菜单列表
     * @return 权限主键到权限码的映射
     */
    private Map<Long, String> permissionCodes(List<Menu> menus) {
        List<Long> ids = menus.stream()
                .map(Menu::getPermissionId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return permissionManager.listByIds(ids).stream()
                .collect(Collectors.toMap(
                        club.beenest.iam.persistence.entity.Permission::getId,
                        club.beenest.iam.persistence.entity.Permission::getPermissionCode,
                        (left, right) -> left));
    }

    /**
     * 扁平菜单转树,按排序号与主键稳定排序。
     *
     * @param menus           扁平菜单列表
     * @param permissionCodes 权限主键到权限码的映射
     * @return 根节点列表
     */
    private static List<ConsoleMenuNodeResponse> buildTree(List<Menu> menus, Map<Long, String> permissionCodes) {
        return menus.stream()
                .filter(menu -> menu.getParentId() == null)
                .sorted(Comparator.comparing(Menu::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Menu::getId))
                .map(menu -> toNode(menu, menus, permissionCodes))
                .toList();
    }

    /**
     * 菜单转节点,递归装配子节点。
     *
     * @param menu            当前菜单
     * @param all             全部菜单
     * @param permissionCodes 权限主键到权限码的映射
     * @return 菜单节点
     */
    private static ConsoleMenuNodeResponse toNode(Menu menu, List<Menu> all, Map<Long, String> permissionCodes) {
        List<ConsoleMenuNodeResponse> children = all.stream()
                .filter(item -> menu.getId().equals(item.getParentId()))
                .sorted(Comparator.comparing(Menu::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Menu::getId))
                .map(item -> toNode(item, all, permissionCodes))
                .toList();
        return new ConsoleMenuNodeResponse(
                menu.getMenuCode(),
                menu.getMenuName(),
                menu.getMenuType(),
                menu.getPath(),
                menu.getIcon(),
                menu.getSortOrder(),
                menu.getPermissionId() == null ? null : permissionCodes.get(menu.getPermissionId()),
                children);
    }
}
