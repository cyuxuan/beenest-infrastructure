package club.beenest.payment.payscore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建服务订单请求DTO（API模块 - Feign客户端使用）
 *
 * @author System
 * @since 2026-06-15
 */
@Schema(name = "ServiceOrderCreateDTO", description = "创建服务订单请求参数")
@Data
public class ServiceOrderCreateDTO {

    @Schema(description = "关联业务单号", example = "MCH202606151234567890")
    @NotBlank(message = "业务单号不能为空")
    private String bizNo;

    @Schema(description = "业务类型", example = "MERCHANT_DEPOSIT")
    private String bizType;

    @Schema(description = "业务系统标识，由拦截器自动注入", hidden = true)
    private String appId;

    @Schema(description = "支付分平台", example = "WECHAT_PAYSCORE",
            allowableValues = {"WECHAT_PAYSCORE", "ALIPAY_ZHIMA"})
    @NotBlank(message = "支付分平台不能为空")
    private String platform;

    @Schema(description = "保证金金额（分）", example = "100000")
    @NotNull(message = "保证金金额不能为空")
    @Min(value = 1, message = "保证金金额必须大于0")
    private Long depositAmount;

    @Schema(description = "渠道用户标识")
    private String channelUserId;

    @Schema(description = "备注信息")
    private String remark;
}
