package club.beenest.payment.paymentorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 订单支付/充值创建结果 DTO
 * 统一 createRechargeOrder、createOrderPayment、handleExistingPendingOrder、createNewOrderPayment 的返回结构。
 *
 * @author System
 * @since 2026-04-22
 */
@Schema(name = "OrderPaymentResultDTO", description = "订单支付创建结果")
@Data
@Accessors(chain = true)
public class OrderPaymentResultDTO {

    @Schema(description = "支付订单号")
    private String orderNo;

    @Schema(description = "业务单号（订单支付模式）")
    private String bizNo;

    @Schema(description = "实际支付金额（分）")
    private Long amount;

    @Schema(description = "原始金额（分）")
    private Long originalAmount;

    @Schema(description = "优惠金额（分）")
    private Long discountAmount;

    @Schema(description = "支付平台")
    private String platform;

    @Schema(description = "支付渠道方式")
    private String paymentMethod;

    @Schema(description = "支付平台中文名")
    private String platformName;

    @Schema(description = "订单过期时间")
    private LocalDateTime expireTime;

    @Schema(description = "前端调起支付所需的参数（平台特定）")
    private Map<String, Object> paymentParams;
}
