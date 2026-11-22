package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.DashboardStatsResponse;

/**
 * 运营仪表盘服务。
 * <p>全部指标按当前身份域口径汇总,覆盖账号规模、MFA 覆盖率、当日登录成败与活跃会话。</p>
 */
public interface DashboardService {

    /**
     * 汇总当前域运营指标。
     *
     * @return 仪表盘统计结果
     */
    DashboardStatsResponse stats();
}
