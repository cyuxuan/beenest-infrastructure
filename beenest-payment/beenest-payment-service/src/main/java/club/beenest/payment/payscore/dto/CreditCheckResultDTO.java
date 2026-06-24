package club.beenest.payment.payscore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 信用免押检查结果DTO
 * 查询用户是否满足信用免押条件
 *
 * @author System
 * @since 2026-06-15
 */
@Schema(name = "CreditCheckResultDTO", description = "信用免押检查结果")
@Data
@Accessors(chain = true)
public class CreditCheckResultDTO {

    /**
     * 用户/商户编号
     */
    @Schema(description = "用户/商户编号")
    private String customerNo;

    /**
     * 支付分平台
     */
    @Schema(description = "支付分平台")
    private String platform;

    /**
     * 是否满足免押条件
     */
    @Schema(description = "是否满足免押条件")
    private boolean eligible;

    /**
     * 免押结果
     * FULL_EXEMPT: 完全免押
     * PARTIAL_EXEMPT: 部分免押
     * NOT_EXEMPT: 不满足免押
     */
    @Schema(description = "免押结果", allowableValues = {"FULL_EXEMPT", "PARTIAL_EXEMPT", "NOT_EXEMPT"})
    private String exemptionResult;

    /**
     * 信用分
     */
    @Schema(description = "信用分")
    private Integer creditScore;

    /**
     * 原始保证金金额（分）
     */
    @Schema(description = "原始保证金金额（分）")
    private Long depositAmount;

    /**
     * 实际需冻结金额（分）
     * 完全免押时为0，部分免押时为减免后金额
     */
    @Schema(description = "实际需冻结金额（分）")
    private Long frozenAmount;

    /**
     * 免押说明
     */
    @Schema(description = "免押说明")
    private String message;
}
