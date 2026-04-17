package club.beenest.payment.object.dto;

import lombok.Data;

/**
 * 对账差异明细（不匹配的记录）
 *
 * @author System
 * @since 2026-04-08
 */
@Data
public class ReconciliationDetailDTO {

    /**
     * 本地订单号
     */
    private String orderNo;

    /**
     * 差异类型
     * AMOUNT_MISMATCH - 金额不一致
     * STATUS_MISMATCH - 状态不一致
     * LOCAL_ONLY - 仅本地有记录，平台无
     * PLATFORM_ONLY - 仅平台有记录，本地无
     */
    private String type;

    /**
     * 本地金额（分）
     */
    private Long localAmount;

    /**
     * 平台金额（分）
     */
    private Long platformAmount;

    /**
     * 本地状态
     */
    private String localStatus;

    /**
     * 平台状态
     */
    private String platformStatus;

    /**
     * 第三方交易号
     */
    private String transactionNo;

    /**
     * 差异描述
     */
    private String description;
}
