package club.beenest.payment.reconciliation.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 第三方平台交易记录（对账用）
 * 统一各平台返回的交易数据格式
 *
 * @author System
 * @since 2026-04-08
 */
@Data
public class PlatformOrderItem {

    /**
     * 本地订单号（商户侧 out_trade_no）
     */
    private String orderNo;

    /**
     * 第三方交易号（微信 transaction_id / 支付宝 trade_no / 抖音 order_id）
     */
    private String transactionNo;

    /**
     * 交易金额（分）
     */
    private Long amount;

    /**
     * 交易状态（SUCCESS/TRADE_SUCCESS/PAID 等）
     */
    private String status;

    /**
     * 支付完成时间
     */
    private LocalDateTime paidTime;

    /**
     * 原始数据（JSON，用于审计追溯）
     */
    private String rawData;
}
