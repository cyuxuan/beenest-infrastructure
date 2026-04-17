package club.beenest.payment.object.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 管理员退款申请 DTO
 * 替代 Map<String, Object> 参数，提供强类型校验
 *
 * @author System
 * @since 2026-04-08
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
