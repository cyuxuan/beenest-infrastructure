package club.beenest.payment.reconciliation.service;

import club.beenest.payment.reconciliation.dto.PlatformOrderItem;

import java.util.List;

/**
 * 对账策略接口
 * 每个支付平台实现此接口，提供从第三方获取交易记录的能力
 *
 * <p>混合策略：优先下载账单（高效），失败时降级为逐笔查询（可靠）</p>
 *
 * @author System
 * @since 2026-04-08
 */
public interface IReconciliationStrategyService {

    /**
     * 获取对应的支付平台标识
     *
     * @return 平台标识（WECHAT / ALIPAY / DOUYIN）
     */
    String getPlatform();

    /**
     * 从第三方平台获取指定日期的交易记录
     *
     * <p>优先使用账单下载 API（批量获取，效率高），
     * 如果不可用或失败则降级为逐笔查询模式</p>
     *
     * @param date 对账日期，格式 yyyy-MM-dd
     * @return 平台侧交易记录列表
     */
    List<PlatformOrderItem> fetchPlatformOrders(String date);
}
