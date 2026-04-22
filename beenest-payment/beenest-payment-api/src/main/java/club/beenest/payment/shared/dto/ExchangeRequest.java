package club.beenest.payment.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 兑换优惠券请求
 */
@Schema(name = "ExchangeRequest", description = "兑换优惠券请求")
@Data
public class ExchangeRequest {
    
    @Schema(description = "兑换码", example = "ABCD1234")
    @NotBlank(message = "兑换码不能为空")
    @Size(min = 6, max = 20, message = "兑换码长度必须在6-20位之间")
    @Pattern(regexp = "^[A-Z0-9]+$", message = "兑换码只能包含大写字母和数字")
    private String code;
}
