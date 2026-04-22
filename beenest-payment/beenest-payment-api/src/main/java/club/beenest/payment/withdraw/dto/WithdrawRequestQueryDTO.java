package club.beenest.payment.withdraw.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提现申请查询参数
 *
 * @author System
 * @since 2026-02-11
 */
@Data
@Schema(name = "WithdrawRequestQueryDTO", description = "提现申请查询参数")
public class WithdrawRequestQueryDTO {

    @Schema(description = "申请号")
    private String requestNo;

    @Schema(description = "用户编号")
    private String customerNo;

    @Schema(description = "申请状态")
    private String status;

    @Schema(description = "提现类型")
    private String withdrawType;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;
}
