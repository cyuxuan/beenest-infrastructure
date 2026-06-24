package club.beenest.payment.payscore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建服务订单请求DTO
 * 用于发起支付分信用免押授权
 *
 * @author System
 * @since 2026-06-15
 */
@Schema(name = "ServiceOrderCreateDTO", description = "创建服务订单请求参数")
@Data
public class ServiceOrderCreateDTO {

    /**
     * 关联业务单号（入驻申请编号）
     */
    @Schema(description = "关联业务单号（入驻申请编号）", example = "MCH202606151234567890")
    @NotBlank(message = "业务单号不能为空")
    private String bizNo;

    /**
     * 业务类型
     */
    @Schema(description = "业务类型", example = "MERCHANT_DEPOSIT")
    private String bizType;

    /**
     * 支付分平台
     * WECHAT_PAYSCORE: 微信支付分
     * ALIPAY_ZHIMA: 支付宝芝麻信用
     */
    @Schema(description = "支付分平台", example = "WECHAT_PAYSCORE",
            allowableValues = {"WECHAT_PAYSCORE", "ALIPAY_ZHIMA"})
    @NotBlank(message = "支付分平台不能为空")
    private String platform;

    /**
     * 保证金金额（分）
     */
    @Schema(description = "保证金金额（分）", example = "100000", minimum = "1")
    @NotNull(message = "保证金金额不能为空")
    @Min(value = 1, message = "保证金金额必须大于0")
    private Long depositAmount;

    /**
     * 渠道用户标识（可选）
     * 微信 JSAPI 场景需传入 openid
     */
    @Schema(description = "渠道用户标识（微信openid/支付宝userId）", example = "o2xYH5a8HnR1ExampleOpenid")
    private String channelUserId;

    /**
     * 备注信息（可选）
     */
    @Schema(description = "备注信息", example = "商户入驻免押")
    private String remark;
}
