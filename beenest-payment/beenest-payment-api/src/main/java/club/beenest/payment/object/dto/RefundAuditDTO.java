package club.beenest.payment.object.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 管理员退款审核 DTO
 * 替代 Map<String, Object> 参数，提供强类型校验
 *
 * @author System
 * @since 2026-04-08
 */
@Data
public class RefundAuditDTO {

    @NotNull(message = "退款记录ID不能为空")
    private Long id;

    /**
     * 审核状态，仅允许 SUCCESS 或 REJECTED
     */
    @NotBlank(message = "审核状态不能为空")
    @Pattern(regexp = "^(SUCCESS|REJECTED)$", message = "审核状态仅允许 SUCCESS 或 REJECTED")
    private String status;

    private String remark;
}
