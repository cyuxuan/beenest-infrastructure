package club.beenest.payment.withdraw.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.validation.constraints.NotNull;

/**
 * 提现审核参数
 *
 * @author System
 * @since 2026-02-11
 */
@Data
@Schema(name = "WithdrawAuditDTO", description = "提现审核参数")
public class WithdrawAuditDTO {

    @Schema(description = "申请ID")
    @NotNull(message = "申请ID不能为空")
    private Long id;

    @Schema(description = "审核状态: APPROVED-通过, REJECTED-拒绝")
    @NotNull(message = "审核状态不能为空")
    private String status;

    @Schema(description = "审核备注")
    private String remark;
    
    @Schema(description = "审核人")
    private String auditBy;
}
