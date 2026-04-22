package club.beenest.payment.paymentorder.dto;

import club.beenest.payment.shared.constant.PaymentConstants;
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
     * 业务单号（由调用方传入）
     */
    @Schema(description = "业务单号", example = "PLN202603041234567890")
    @NotBlank(message = "业务单号不能为空")
    private String bizNo;

    /**
     * 支付金额（分）
     */
    @Schema(description = "支付金额（分，兼容旧端，可不传）", example = "10000", minimum = "1")
    private Long amount;

    /**
     * 渠道用户标识（可选）。
     *
     * <p>微信 JSAPI / 小程序支付时，调用方可以直接传入已认证的 openid，
     * payment 中台会优先使用该值；若未传，则继续从当前认证上下文中解析。</p>
     */
    @Schema(description = "渠道用户标识", example = "o2xYH5a8HnR1ExampleOpenid")
    private String openid;

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
     * 支付渠道方式。
     *
     * <p>微信 App 支付默认使用 `WECHAT_APP`，微信内 / 小程序支付可显式传 `WECHAT_JSAPI`。</p>
     */
    @Schema(description = "支付渠道方式", example = "WECHAT_APP",
            allowableValues = {"WECHAT_APP", "WECHAT_JSAPI", "ALIPAY_APP", "ALIPAY_JSAPI", "DOUYIN_APP", "DOUYIN_MINI"})
    private String paymentMethod;

    /**
     * 平台标识
     * app: 原生 App
     * mp-weixin: 微信小程序
     * mp-alipay: 支付宝小程序
     * mp-toutiao: 抖音小程序
     */
    @Schema(description = "平台标识", example = "mp-weixin",
            allowableValues = {"app", "mp-weixin", "mp-alipay", "mp-toutiao"})
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

    /**
     * 获取默认支付渠道方式。
     *
     * <p>若请求未显式指定 paymentMethod，则根据平台自动推导。
     * 微信默认使用 App 支付，只有明确传入 `WECHAT_JSAPI` 才会要求 openid。</p>
     *
     * @return 支付渠道方式
     */
    public String getDefaultPaymentMethod() {
        if (paymentMethod != null && !paymentMethod.trim().isEmpty()) {
            return paymentMethod;
        }
        String backendPlatform = getBackendPlatform();
        if (backendPlatform == null) {
            return null;
        }
        return switch (backendPlatform) {
            case PaymentConstants.PLATFORM_WECHAT -> PaymentConstants.METHOD_WECHAT_APP;
            case PaymentConstants.PLATFORM_ALIPAY -> PaymentConstants.METHOD_ALIPAY_APP;
            case PaymentConstants.PLATFORM_DOUYIN -> PaymentConstants.METHOD_DOUYIN_MINI;
            default -> null;
        };
    }
}
