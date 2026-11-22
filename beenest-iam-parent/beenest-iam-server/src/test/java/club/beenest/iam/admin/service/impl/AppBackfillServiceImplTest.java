package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.AppBackfillImportRequest;
import club.beenest.iam.admin.dto.AppBackfillReportResponse;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Menu;
import club.beenest.iam.persistence.entity.Permission;
import club.beenest.iam.persistence.manager.IamAppBackfillManager;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamMenuManager;
import club.beenest.iam.persistence.manager.IamPermissionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用权限回填服务测试。
 * <p>锁定三类契约:报告只统计本应用缺口、跨域资源按不存在处理、
 * 策略决定走归属迁移还是复制到应用。</p>
 */
class AppBackfillServiceImplTest {

    private static final Long REALM_ID = 1L;
    private static final Long OTHER_REALM_ID = 2L;
    private static final Long APP_ID = 7L;
    private static final Long USER_ID = 9L;
    private static final Long PERMISSION_ID = 72L;

    private final IamApplicationServiceManager appServiceManager = mock(IamApplicationServiceManager.class);
    private final IamPermissionManager permissionManager = mock(IamPermissionManager.class);
    private final IamMenuManager menuManager = mock(IamMenuManager.class);
    private final IamAppBackfillManager backfillManager = mock(IamAppBackfillManager.class);

    private final AppBackfillServiceImpl service = new AppBackfillServiceImpl(appServiceManager,
            permissionManager, menuManager, backfillManager);

    @BeforeEach
    void setUp() {
        login();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("报告按策略与共享情况汇总缺口")
    void reportSummarizesGaps() {
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID, REALM_ID));
        when(backfillManager.analyze(REALM_ID, APP_ID)).thenReturn(new IamAppBackfillManager.BackfillAnalysis(
                List.of(candidate(1L, "PERMISSION", "expense:view", IamAppBackfillManager.STRATEGY_MOVE, List.of()),
                        candidate(2L, "PERMISSION", "other:view", IamAppBackfillManager.STRATEGY_COPY, List.of("report"))),
                List.of(candidate(3L, "MENU", "expense.menu", IamAppBackfillManager.STRATEGY_MOVE, List.of("report")))));

        AppBackfillReportResponse report = service.report(APP_ID);

        assertEquals(2, report.permissions().size());
        assertEquals(1, report.menus().size());
        assertEquals(2, report.movableCount());
        assertEquals(1, report.copyOnlyCount());
        assertEquals(2, report.sharedCount());
    }

    @Test
    @DisplayName("跨域应用按不存在处理")
    void rejectsCrossRealmApp() {
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID, OTHER_REALM_ID));

        BusinessException error = assertThrows(BusinessException.class, () -> service.report(APP_ID));

        assertTrue(error.getMessage().contains("应用服务不存在"));
        verify(backfillManager, never()).analyze(any(), any());
    }

    @Test
    @DisplayName("默认走归属迁移,指定 COPY 时复制到应用")
    void importUsesSelectedStrategy() {
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID, REALM_ID));
        when(permissionManager.listByIds(List.of(PERMISSION_ID))).thenReturn(List.of(permission(PERMISSION_ID, REALM_ID)));
        when(menuManager.listByIds(List.of())).thenReturn(List.of());
        when(backfillManager.moveToApp(REALM_ID, APP_ID, List.of(PERMISSION_ID), List.of()))
                .thenReturn(new IamAppBackfillManager.BackfillOutcome(1, 0, 0, 0, 0, List.of()));
        when(backfillManager.copyToApp(REALM_ID, APP_ID, List.of(PERMISSION_ID), List.of()))
                .thenReturn(new IamAppBackfillManager.BackfillOutcome(0, 0, 1, 0, 2, List.of("提示")));

        assertEquals(1, service.importBackfill(APP_ID,
                new AppBackfillImportRequest(List.of(PERMISSION_ID), List.of(), null)).movedPermissions());
        assertEquals(1, service.importBackfill(APP_ID,
                new AppBackfillImportRequest(List.of(PERMISSION_ID), List.of(), "COPY")).copiedPermissions());

        verify(backfillManager).moveToApp(eq(REALM_ID), eq(APP_ID), eq(List.of(PERMISSION_ID)), eq(List.of()));
        verify(backfillManager).copyToApp(eq(REALM_ID), eq(APP_ID), eq(List.of(PERMISSION_ID)), eq(List.of()));
    }

    @Test
    @DisplayName("跨域资源主键按不存在处理,不做回填")
    void rejectsCrossRealmResources() {
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID, REALM_ID));
        when(permissionManager.listByIds(List.of(PERMISSION_ID)))
                .thenReturn(List.of(permission(PERMISSION_ID, OTHER_REALM_ID)));

        BusinessException error = assertThrows(BusinessException.class, () -> service.importBackfill(APP_ID,
                new AppBackfillImportRequest(List.of(PERMISSION_ID), List.of(), "MOVE")));

        assertTrue(error.getMessage().contains("权限不存在"));
        verify(backfillManager, never()).moveToApp(any(), any(), any(), any());
    }

    private static IamAppBackfillManager.BackfillCandidate candidate(Long id, String kind, String code,
                                                                     String strategy, List<String> sharedApps) {
        return new IamAppBackfillManager.BackfillCandidate(kind, id, code, code, true, null, null, strategy,
                List.of("角色直授"), List.of("expense-auditor"), List.of(), sharedApps);
    }

    private static void login() {
        IamUserPrincipal principal = new IamUserPrincipal(USER_ID, REALM_ID, "default", "alice",
                "Alice", 1, List.of(), Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static ApplicationService app(Long id, Long realmId) {
        ApplicationService app = new ApplicationService();
        app.setId(id);
        app.setRealmId(realmId);
        app.setAppCode("expense");
        app.setAppName("报销中心");
        app.setTokenProfile("LEGACY_GLOBAL");
        return app;
    }

    private static Permission permission(Long id, Long realmId) {
        Permission permission = new Permission();
        permission.setId(id);
        permission.setRealmId(realmId);
        permission.setPermissionCode("expense:view");
        return permission;
    }

    @SuppressWarnings("unused")
    private static Menu menu(Long id, Long realmId) {
        Menu menu = new Menu();
        menu.setId(id);
        menu.setRealmId(realmId);
        return menu;
    }
}
