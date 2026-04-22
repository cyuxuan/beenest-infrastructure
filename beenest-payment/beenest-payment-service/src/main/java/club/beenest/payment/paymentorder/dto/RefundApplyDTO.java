package club.beenest.payment.paymentorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 管理员退款申请 DTO
 */
@Data
public class RefundApplyDTO {

    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    @NotNull(message = "退款金额不能为空")
    @Positive(message = "退款金额必须大于0")
    private Long amount;

    private String reason;
}
