package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.AppBackfillImportRequest;
import club.beenest.iam.admin.dto.AppBackfillImportResponse;
import club.beenest.iam.admin.dto.AppBackfillReportResponse;
import club.beenest.iam.admin.dto.AppBackfillReportResponse.BackfillCandidate;
import club.beenest.iam.admin.service.AppBackfillService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Menu;
import club.beenest.iam.persistence.entity.Permission;
import club.beenest.iam.persistence.manager.IamAppBackfillManager;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamMenuManager;
import club.beenest.iam.persistence.manager.IamPermissionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 应用权限回填实现。
 * <p>统计并回填应用实际使用、仍挂在平台命名空间下的权限与菜单,支持归属迁移(MOVE)
 * 与复制(COPY)两种策略;导入在单个事务内完成。</p>
 */
@Service
public class AppBackfillServiceImpl implements AppBackfillService {

    /** 策略:归属迁移(仅平台级资源) */
    private static final String STRATEGY_MOVE = "MOVE";
    /** 策略:复制到应用 */
    private static final String STRATEGY_COPY = "COPY";

    private final IamApplicationServiceManager appServiceManager;
    private final IamPermissionManager permissionManager;
    private final IamMenuManager menuManager;
    private final IamAppBackfillManager backfillManager;

    public AppBackfillServiceImpl(IamApplicationServiceManager appServiceManager,
                                  IamPermissionManager permissionManager,
                                  IamMenuManager menuManager,
                                  IamAppBackfillManager backfillManager) {
        this.appServiceManager = appServiceManager;
        this.permissionManager = permissionManager;
        this.menuManager = menuManager;
        this.backfillManager = backfillManager;
    }

    @Override
    public AppBackfillReportResponse report(Long appId) {
        ApplicationService app = requireAppInRealm(appId);
        IamAppBackfillManager.BackfillAnalysis analysis = backfillManager.analyze(app.getRealmId(), appId);
        List<BackfillCandidate> permissions = analysis.permissions().stream()
                .map(AppBackfillServiceImpl::toCandidate)
                .toList();
        List<BackfillCandidate> menus = analysis.menus().stream()
                .map(AppBackfillServiceImpl::toCandidate)
                .toList();
        List<BackfillCandidate> all = new ArrayList<>(permissions);
        all.addAll(menus);
        int movable = (int) all.stream().filter(candidate -> STRATEGY_MOVE.equals(candidate.strategy())).count();
        int copyOnly = (int) all.stream().filter(candidate -> STRATEGY_COPY.equals(candidate.strategy())).count();
        int shared = (int) all.stream().filter(candidate -> !candidate.sharedAppCodes().isEmpty()).count();
        return new AppBackfillReportResponse(app.getId(), app.getAppCode(), app.getAppName(),
                app.getTokenProfile(), permissions, menus, movable, copyOnly, shared);
    }

    @Override
    @Transactional
    public AppBackfillImportResponse importBackfill(Long appId, AppBackfillImportRequest request) {
        ApplicationService app = requireAppInRealm(appId);
        Long realmId = app.getRealmId();
        String strategy = request.strategy() == null || request.strategy().isBlank()
                ? STRATEGY_MOVE : request.strategy().toUpperCase();
        List<Long> permissionIds = distinct(request.permissionIds());
        List<Long> menuIds = distinct(request.menuIds());
        requireInRealm(permissionIds, menuIds, realmId);

        IamAppBackfillManager.BackfillOutcome outcome = STRATEGY_COPY.equals(strategy)
                ? backfillManager.copyToApp(realmId, appId, permissionIds, menuIds)
                : backfillManager.moveToApp(realmId, appId, permissionIds, menuIds);
        return new AppBackfillImportResponse(outcome.movedPermissions(), outcome.movedMenus(),
                outcome.copiedPermissions(), outcome.copiedMenus(), outcome.relinkedGrants(),
                outcome.conflicts());
    }

    /**
     * 校验选中的资源全部属于当前身份域。
     *
     * @param permissionIds 权限主键
     * @param menuIds       菜单主键
     * @param realmId       身份域主键
     * @throws BusinessException 存在缺失或跨域的权限、菜单
     */
    private void requireInRealm(List<Long> permissionIds, List<Long> menuIds, Long realmId) {
        List<Permission> permissions = permissionManager.listByIds(permissionIds);
        if (permissions.size() != permissionIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "权限不存在");
        }
        for (Permission permission : permissions) {
            if (!realmId.equals(permission.getRealmId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "权限不存在: " + permission.getId());
            }
        }
        List<Menu> menus = menuManager.listByIds(menuIds);
        if (menus.size() != menuIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "菜单不存在");
        }
        for (Menu menu : menus) {
            if (!realmId.equals(menu.getRealmId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "菜单不存在: " + menu.getId());
            }
        }
    }

    /**
     * 管理器候选对象转响应候选对象。
     *
     * @param candidate 管理器候选对象
     * @return 响应候选对象
     */
    private static BackfillCandidate toCandidate(IamAppBackfillManager.BackfillCandidate candidate) {
        return new BackfillCandidate(candidate.kind(), candidate.id(), candidate.code(), candidate.name(),
                candidate.platform(), candidate.ownerAppCode(), candidate.strategy(), candidate.reasons(),
                candidate.roleCodes(), candidate.realmRoleCodes(), candidate.sharedAppCodes());
    }

    /**
     * 读取属于当前域的应用服务。
     *
     * @param appId 应用服务主键
     * @return 应用服务
     * @throws BusinessException 应用不存在或不属于当前域
     */
    private ApplicationService requireAppInRealm(Long appId) {
        ApplicationService app = appId == null ? null : appServiceManager.getById(appId);
        if (app == null || !currentRealmId().equals(app.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "应用服务不存在: " + appId);
        }
        return app;
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
     * 去重并剔除空主键。
     *
     * @param ids 原始主键列表,可为 null
     * @return 去重后的主键列表
     */
    private static List<Long> distinct(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }
}
