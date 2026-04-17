package club.beenest.payment.object.dto;

import club.beenest.payment.constant.PaymentConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 充值请求DTO
 * 用于接收用户充值请求参数
 * 
 * <p>包含充值所需的基本信息，如金额、支付平台等</p>
 * 
 * @author System
 * @since 2026-01-26
 */
@Schema(name = "RechargeRequestDTO", description = "充值请求参数")
@Data
public class RechargeRequestDTO {
    
    /**
     * 充值金额（分）
     * 最小值为100分（1元）
     */
    @Schema(description = "充值金额（分）", example = "10000", minimum = "100")
    @NotNull(message = "充值金额不能为空")
    @Min(value = 100, message = "充值金额不能少于1元")
    private Long amount;
    
    /**
     * 支付平台
     * WECHAT: 微信支付
     * ALIPAY: 支付宝
     * DOUYIN: 抖音支付
     */
    @Schema(description = "支付平台", example = "WECHAT", 
            allowableValues = {"WECHAT", "ALIPAY", "DOUYIN"})
    @NotBlank(message = "支付平台不能为空")
    private String platform;
    
    /**
     * 支付方式（可选）
     * 如果不指定，系统会根据平台自动选择
     */
    @Schema(description = "支付方式", example = "WECHAT_MINI", 
            allowableValues = {"WECHAT_MINI", "ALIPAY_MINI", "DOUYIN_MINI"})
    private String paymentMethod;
    
    /**
     * 支付完成跳转地址（可选）
     */
    @Schema(description = "支付完成跳转地址", 
            example = "https://app.example.com/payment/success")
    private String returnUrl;
    
    /**
     * 备注信息（可选）
     */
    @Schema(description = "备注信息", example = "用户充值")
    private String remark;
    
    // ==================== 业务方法 ====================
    
    /**
     * 获取充值金额（元）
     * 将分转换为元，保留两位小数
     * 
     * @return 充值金额（元）
     */
    public BigDecimal getAmountInYuan() {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(amount).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }
    
    /**
     * 验证支付平台是否有效
     * 
     * @return true表示有效，false表示无效
     */
    public boolean isValidPlatform() {
        if (platform == null) {
            return false;
        }
        return PaymentConstants.PLATFORM_WECHAT.equals(platform) || 
               PaymentConstants.PLATFORM_ALIPAY.equals(platform) || 
               PaymentConstants.PLATFORM_DOUYIN.equals(platform);
    }
    
    public String getDefaultPaymentMethod() {
        if (paymentMethod != null && !paymentMethod.trim().isEmpty()) {
            return paymentMethod;
        }
        
        if (platform == null) {
            return null;
        }

        return switch (platform) {
            case PaymentConstants.PLATFORM_WECHAT -> PaymentConstants.METHOD_WECHAT_MINI;
            case PaymentConstants.PLATFORM_ALIPAY -> PaymentConstants.METHOD_ALIPAY_MINI;
            case PaymentConstants.PLATFORM_DOUYIN -> PaymentConstants.METHOD_DOUYIN_MINI;
            default -> null;
        };
    }
    
    /**
     * 验证充值金额是否在合理范围内
     * 
     * @return true表示合理，false表示不合理
     */
    public boolean isValidAmount() {
        if (amount == null) {
            return false;
        }
        
        // 最小充值金额：1元（100分）
        // 最大充值金额：10万元（10000000分）
        return amount >= 100 && amount <= 10000000;
    }
}