package club.beenest.payment.payscore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 服务订单返回结果DTO（API模块 - Feign客户端使用）
 *
 * @author System
 * @since 2026-06-15
 */
@Schema(name = "ServiceOrderResultDTO", description = "服务订单返回结果")
@Data
@Accessors(chain = true)
public class ServiceOrderResultDTO {

    @Schema(description = "服务订单号")
    private String orderNo;

    @Schema(description = "关联业务单号")
    private String bizNo;

    @Schema(description = "支付分平台")
    private String platform;

    @Schema(description = "平台显示名称")
    private String platformName;

    @Schema(description = "服务订单状态")
    private String status;

    @Schema(description = "状态显示名称")
    private String statusDisplayName;

    @Schema(description = "原始保证金金额（分）")
    private Long depositAmount;

    @Schema(description = "实际冻结金额（分）")
    private Long frozenAmount;

    @Schema(description = "实际扣款金额（分）")
    private Long actualAmount;

    @Schema(description = "授权跳转参数")
    private Map<String, Object> authParams;

    @Schema(description = "过期时间")
    private LocalDateTime expireTime;

    @Schema(description = "授权时间")
    private LocalDateTime authTime;

    @Schema(description = "完结时间")
    private LocalDateTime completeTime;
}
