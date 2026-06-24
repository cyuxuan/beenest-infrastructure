package club.beenest.payment.payscore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 服务订单返回结果DTO
 * 统一服务订单操作的返回结构
 *
 * @author System
 * @since 2026-06-15
 */
@Schema(name = "ServiceOrderResultDTO", description = "服务订单返回结果")
@Data
@Accessors(chain = true)
public class ServiceOrderResultDTO {

    /**
     * 服务订单号
     */
    @Schema(description = "服务订单号")
    private String orderNo;

    /**
     * 关联业务单号
     */
    @Schema(description = "关联业务单号")
    private String bizNo;

    /**
     * 支付分平台
     */
    @Schema(description = "支付分平台")
    private String platform;

    /**
     * 平台显示名称
     */
    @Schema(description = "平台显示名称")
    private String platformName;

    /**
     * 服务订单状态
     */
    @Schema(description = "服务订单状态")
    private String status;

    /**
     * 状态显示名称
     */
    @Schema(description = "状态显示名称")
    private String statusDisplayName;

    /**
     * 原始保证金金额（分）
     */
    @Schema(description = "原始保证金金额（分）")
    private Long depositAmount;

    /**
     * 实际冻结金额（分）
     */
    @Schema(description = "实际冻结金额（分）")
    private Long frozenAmount;

    /**
     * 实际扣款金额（分）
     */
    @Schema(description = "实际扣款金额（分）")
    private Long actualAmount;

    /**
     * 授权跳转参数（创建服务订单时返回，前端用于跳转授权页面）
     */
    @Schema(description = "授权跳转参数")
    private Map<String, Object> authParams;

    /**
     * 过期时间
     */
    @Schema(description = "过期时间")
    private LocalDateTime expireTime;

    /**
     * 授权时间
     */
    @Schema(description = "授权时间")
    private LocalDateTime authTime;

    /**
     * 完结时间
     */
    @Schema(description = "完结时间")
    private LocalDateTime completeTime;
}
