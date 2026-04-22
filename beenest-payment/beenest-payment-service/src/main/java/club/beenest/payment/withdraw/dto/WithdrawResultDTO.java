package club.beenest.payment.withdraw.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 提现申请结果 DTO
 * 替代 createWithdrawRequest 和 getWithdrawRequestStatus 方法中的 Map 返回值。
 *
 * @author System
 * @since 2026-04-22
 */
@Schema(name = "WithdrawResultDTO", description = "提现申请结果")
@Data
@Accessors(chain = true)
public class WithdrawResultDTO {

    @Schema(description = "提现申请号")
    private String requestNo;

    @Schema(description = "提现金额（分）")
    private Long amount;

    @Schema(description = "手续费（分）")
    private Long feeAmount;

    @Schema(description = "实际到账金额（分）")
    private Long actualAmount;

    @Schema(description = "提现平台")
    private String platform;

    @Schema(description = "申请状态")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "审核时间")
    private LocalDateTime auditTime;

    @Schema(description = "处理完成时间")
    private LocalDateTime processTime;
}
