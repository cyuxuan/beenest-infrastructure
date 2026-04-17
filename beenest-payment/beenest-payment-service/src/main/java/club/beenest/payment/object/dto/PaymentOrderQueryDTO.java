package club.beenest.payment.object.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 充值订单查询参数
 *
 * @author System
 * @since 2026-02-11
 */
@Data
@Schema(name = "PaymentOrderQueryDTO", description = "充值订单查询参数")
public class PaymentOrderQueryDTO {

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "用户编号")
    private String customerNo;

    @Schema(description = "订单状态")
    private String status;

    @Schema(description = "支付平台")
    private String platform;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;
}
