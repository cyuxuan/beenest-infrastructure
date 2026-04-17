package club.beenest.payment.object.dto;

import club.beenest.payment.constant.PaymentConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 订单支付请求DTO
 * 用于接收用户下单支付请求参数
 *
 * @author System
 * @since 2026-03-04
 */
@Schema(name = "OrderPaymentRequestDTO", description = "订单支付请求参数")
@Data
public class OrderPaymentRequestDTO {

    /**
     * 计划编号（业务单号）
     */
    @Schema(description = "计划编号", example = "PLN202603041234567890")
    @NotBlank(message = "计划编号不能为空")
    private String planNo;

    /**
     * 支付金额（分）
     */
    @Schema(description = "支付金额（分，兼容旧端，可不传）", example = "10000", minimum = "1")
    private Long amount;

    /**
     * 支付方式
     * wxpay: 微信支付
     * alipay: 支付宝
     * toutiao: 抖音支付
     */
    @Schema(description = "支付方式", example = "wxpay",
            allowableValues = {"wxpay", "alipay", "toutiao"})
    @NotBlank(message = "支付方式不能为空")
    private String payType;

    /**
     * 平台标识
     * mp-weixin: 微信小程序
     * mp-alipay: 支付宝小程序
     * mp-toutiao: 抖音小程序
     */
    @Schema(description = "平台标识", example = "mp-weixin",
            allowableValues = {"mp-weixin", "mp-alipay", "mp-toutiao"})
    @NotBlank(message = "平台标识不能为空")
    private String platform;

    /**
     * 优惠券ID（可选）
     */
    @Schema(description = "优惠券ID", example = "1")
    private Long couponId;

    /**
     * 业务类型（可选，由调用方传入）
     * 如 DRONE_ORDER, SHOP_ORDER 等
     */
    @Schema(description = "业务类型", example = "DRONE_ORDER")
    private String bizType;

    /**
     * 用户openid（微信支付需要）
     */
    @Schema(description = "用户openid（微信支付时必传）")
    private String openid;

    /**
     * 原始金额（分，可选，用于前端展示优惠信息）
     */
    @Schema(description = "原始金额（分）", example = "10000")
    private Long originalAmount;

    /**
     * 优惠金额（分，可选，由调用方计算后传入）
     */
    @Schema(description = "优惠金额（分）", example = "500")
    private Long discountAmount;

    public String getBackendPlatform() {
        if (payType == null) return null;
        return switch (payType) {
            case "wxpay" -> PaymentConstants.PLATFORM_WECHAT;
            case "alipay" -> PaymentConstants.PLATFORM_ALIPAY;
            case "toutiao" -> PaymentConstants.PLATFORM_DOUYIN;
            default -> payType.toUpperCase();
        };
    }
}
