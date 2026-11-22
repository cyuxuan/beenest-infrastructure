package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.DashboardStatsResponse;
import club.beenest.iam.admin.service.DashboardService;
import club.beenest.iam.common.result.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营仪表盘接口。
 * <p>全部指标按当前身份域口径汇总,覆盖账号规模、MFA 覆盖率、当日登录成败与活跃会话。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * 汇总当前身份域运营指标。
     *
     * @return 仪表盘统计结果
     */
    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('dashboard:view')")
    public Result<DashboardStatsResponse> stats() {
        return Result.success(dashboardService.stats());
    }
}
