package club.beenest.payment.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 领取优惠券请求
 */
@Schema(name = "ClaimRequest", description = "领取优惠券请求")
@Data
public class ClaimRequest {
    
    @Schema(description = "优惠券编号", example = "CP20240126001")
    @NotBlank(message = "优惠券编号不能为空")
    private String couponNo;
}
