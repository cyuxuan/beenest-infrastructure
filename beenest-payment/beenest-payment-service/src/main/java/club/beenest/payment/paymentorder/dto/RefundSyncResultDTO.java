package club.beenest.payment.paymentorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 退款同步结果 DTO
 * 替代 syncRefundStatus 和 buildRefundSyncResult 方法中的 Map 返回值。
 *
 * @author System
 * @since 2026-04-22
 */
@Schema(name = "RefundSyncResultDTO", description = "退款同步结果")
@Data
@Accessors(chain = true)
public class RefundSyncResultDTO {

    @Schema(description = "退款单号")
    private String refundNo;

    @Schema(description = "原支付订单号")
    private String orderNo;

    @Schema(description = "支付平台")
    private String platform;

    @Schema(description = "退款状态")
    private String status;

    @Schema(description = "渠道侧退款状态")
    private String channelStatus;

    @Schema(description = "第三方退款单号")
    private String thirdPartyRefundNo;

    @Schema(description = "同步来源")
    private String source;
}
