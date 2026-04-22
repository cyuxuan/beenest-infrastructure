package club.beenest.payment.reconciliation.service;

import club.beenest.payment.reconciliation.dto.ReconciliationQueryDTO;
import club.beenest.payment.reconciliation.domain.entity.ReconciliationTask;
import com.github.pagehelper.Page;

/**
 * 对账服务接口
 * 提供支付对账任务的创建、查询功能
 *
 * <p>对账流程：收集本地订单 → 获取第三方平台账单 → 双边逐条比对 → 记录差异。</p>
 *
 * @author System
 * @since 2026-02-11
 */
public interface IReconciliationService {

    /**
     * 分页查询对账任务
     *
     * @param query    查询条件（日期、渠道、状态等）
     * @param pageNum  页码，从1开始
     * @param pageSize 每页大小
     * @return 对账任务分页列表
     */
    Page<ReconciliationTask> queryTasks(ReconciliationQueryDTO query, int pageNum, int pageSize);

    /**
     * 创建对账任务
     *
     * <p>根据指定日期和支付渠道创建对账任务，自动执行双边对账并记录差异。</p>
     *
     * @param date    对账日期，格式 yyyy-MM-dd
     * @param channel 支付渠道（WECHAT, ALIPAY, DOUYIN）
     * @throws IllegalArgumentException 如果日期格式错误或渠道不支持
     */
    void createTask(String date, String channel);
}
