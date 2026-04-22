package club.beenest.payment.paymentorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 支付状态查询结果 DTO
 * 替代 queryPaymentStatus 方法中的 Map 返回值。
 *
 * @author System
 * @since 2026-04-22
 */
@Schema(name = "PaymentStatusDTO", description = "支付状态查询结果")
@Data
@Accessors(chain = true)
public class PaymentStatusDTO {

    @Schema(description = "支付订单号")
    private String orderNo;

    @Schema(description = "订单状态")
    private String status;

    @Schema(description = "支付金额（分）")
    private Long amount;

    @Schema(description = "支付平台")
    private String platform;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "支付完成时间")
    private LocalDateTime paidTime;

    @Schema(description = "过期时间")
    private LocalDateTime expireTime;

    @Schema(description = "业务单号")
    private String bizNo;
}
