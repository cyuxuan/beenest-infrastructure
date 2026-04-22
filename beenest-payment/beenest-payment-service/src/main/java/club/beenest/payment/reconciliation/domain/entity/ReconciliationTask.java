package club.beenest.payment.reconciliation.domain.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 对账任务实体
 *
 * @author System
 * @since 2026-02-11
 */
@Data
public class ReconciliationTask {
    /**
     * 主键ID
     */
    private Long id;

    /**
     * 对账日期 (yyyy-MM-dd)
     */
    private String date;

    /**
     * 支付渠道 (ALIPAY, WECHAT, DOUYIN)
     */
    private String channel;

    /**
     * 状态 (PENDING, PROCESSING, COMPLETED, MISMATCH, FAILED)
     */
    private String status;

    /**
     * 本地总订单数
     */
    private Integer totalOrders;

    /**
     * 本地总金额（分）
     */
    private Long totalAmount;

    /**
     * 平台账单笔数
     */
    private Integer platformOrderCount;

    /**
     * 平台账单金额（分）
     */
    private Long platformAmount;

    /**
     * 匹配笔数
     */
    private Integer matchCount;

    /**
     * 不匹配笔数
     */
    private Integer mismatchCount;

    /**
     * 不匹配明细（JSON格式）
     */
    private String detail;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
