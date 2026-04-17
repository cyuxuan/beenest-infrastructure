package club.beenest.payment.object.entity;

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
     * 支付渠道 (ALIPAY, WECHAT)
     */
    private String channel;

    /**
     * 状态 (PENDING, PROCESSING, COMPLETED, FAILED)
     */
    private String status;

    /**
     * 总订单数
     */
    private Integer totalOrders;

    /**
     * 总金额（分）
     */
    private Long totalAmount;

    /**
     * 匹配笔数
     */
    private Integer matchCount;

    /**
     * 不匹配笔数
     */
    private Integer mismatchCount;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
