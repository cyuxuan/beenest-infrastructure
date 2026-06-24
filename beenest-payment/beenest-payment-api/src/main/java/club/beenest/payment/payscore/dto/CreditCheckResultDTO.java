package club.beenest.payment.payscore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 信用免押检查结果DTO（API模块 - Feign客户端使用）
 *
 * @author System
 * @since 2026-06-15
 */
@Schema(name = "CreditCheckResultDTO", description = "信用免押检查结果")
@Data
@Accessors(chain = true)
public class CreditCheckResultDTO {

    @Schema(description = "用户/商户编号")
    private String customerNo;

    @Schema(description = "支付分平台")
    private String platform;

    @Schema(description = "是否满足免押条件")
    private boolean eligible;

    @Schema(description = "免押结果", allowableValues = {"FULL_EXEMPT", "PARTIAL_EXEMPT", "NOT_EXEMPT"})
    private String exemptionResult;

    @Schema(description = "信用分")
    private Integer creditScore;

    @Schema(description = "原始保证金金额（分）")
    private Long depositAmount;

    @Schema(description = "实际需冻结金额（分）")
    private Long frozenAmount;

    @Schema(description = "免押说明")
    private String message;
}
