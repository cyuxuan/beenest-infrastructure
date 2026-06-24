package club.beenest.payment.payscore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 取消服务订单请求DTO
 *
 * @author System
 * @since 2026-06-15
 */
@Schema(name = "ServiceOrderCancelDTO", description = "取消服务订单请求参数")
@Data
public class ServiceOrderCancelDTO {

    /**
     * 取消原因（可选）
     */
    @Schema(description = "取消原因", example = "用户主动取消授权")
    private String reason;
}
