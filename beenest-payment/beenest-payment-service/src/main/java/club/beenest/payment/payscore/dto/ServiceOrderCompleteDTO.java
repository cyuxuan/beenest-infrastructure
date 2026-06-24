package club.beenest.payment.payscore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 完结服务订单请求DTO
 * 服务期结束时调用，扣取实际费用并解冻剩余额度
 *
 * @author System
 * @since 2026-06-15
 */
@Schema(name = "ServiceOrderCompleteDTO", description = "完结服务订单请求参数")
@Data
public class ServiceOrderCompleteDTO {

    /**
     * 实际扣款金额（分）
     * 0 表示全额解冻（无扣款）
     */
    @Schema(description = "实际扣款金额（分，0表示全额解冻）", example = "0")
    @NotNull(message = "实际扣款金额不能为空")
    @Min(value = 0, message = "实际扣款金额不能为负数")
    private Long actualAmount;

    /**
     * 完结原因（可选）
     */
    @Schema(description = "完结原因", example = "商户入驻期满，无违约扣款")
    private String reason;
}
