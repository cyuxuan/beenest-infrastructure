package club.beenest.payment.paymentorder.domain.entity;

import club.beenest.payment.shared.constant.PaymentConstants;
import club.beenest.payment.paymentorder.domain.enums.PaymentOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 充值订单实体类
 * 对应数据库表：ds_payment_order
 * 
 * <p>记录用户充值订单信息和支付状态</p>
 * 
 * <h3>主要功能：</h3>
 * <ul>
 *   <li>订单管理 - 创建和管理充值订单</li>
 *   <li>支付集成 - 对接多个支付平台</li>
 *   <li>状态跟踪 - 跟踪支付状态变化</li>
 *   <li>回调处理 - 处理支付平台回调</li>
 * </ul>
 * 
 * <h3>支持平台：</h3>
 * <ul>
 *   <li>WECHAT - 微信支付</li>
 *   <li>ALIPAY - 支付宝</li>
 *   <li>DOUYIN - 抖音支付</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-26
 */
@Schema(name = "PaymentOrder", description = "充值订单实体")
@Data
public class PaymentOrder {
    
    /**
     * 主键ID
     */
    @Schema(description = "主键ID", example = "1")
    private Long id;
    
    /**
     * 订单号
     * 唯一标识，格式：P + 时间戳 + 随机数
     */
    @Schema(description = "订单号", example = "P202601261234567890123")
    private String orderNo;
    
    /**
     * 用户编号
     * 关联用户表的customer_no字段
     */
    @Schema(description = "用户编号", example = "C202601261234567890123")
    private String customerNo;

    /**
     * 钱包编号
     * 关联钱包表的wallet_no字段
     */
    @Schema(description = "钱包编号", example = "W202601261234567890123")
    private String walletNo;
    
    /**
     * 充值金额
     * 单位：分
     */
    @Schema(description = "充值金额（分）", example = "10000")
    private Long amount;
    
    /**
     * 支付平台
     * WECHAT: 微信支付
     * ALIPAY: 支付宝
     * DOUYIN: 抖音支付
     */
    @Schema(description = "支付平台", example = "WECHAT",
            allowableValues = {"WECHAT", "ALIPAY", "DOUYIN"})
    private String platform;
    
    /**
     * 支付方式
     * WECHAT_APP: 微信 App 支付
     * WECHAT_JSAPI: 微信 JSAPI / 小程序支付
     * ALIPAY_APP: 支付宝 App 支付
     * ALIPAY_JSAPI: 支付宝 JSAPI / 小程序支付
     * DOUYIN_APP: 抖音 App 支付
     * DOUYIN_MINI: 抖音小程序支付
     */
    @Schema(description = "支付方式", example = "WECHAT_APP",
            allowableValues = {"WECHAT_APP", "WECHAT_JSAPI", "ALIPAY_APP", "ALIPAY_JSAPI", "DOUYIN_APP", "DOUYIN_MINI"})
    private String paymentMethod;
    
    /**
     * 第三方订单号
     * 支付平台返回的订单号
     */
    @Schema(description = "第三方订单号", example = "wx_order_123456789")
    private String thirdPartyOrderNo;
    
    /**
     * 第三方交易号
     * 支付平台返回的交易号
     */
    @Schema(description = "第三方交易号", example = "wx_transaction_123456789")
    private String thirdPartyTransactionNo;
    
    /**
     * 支付参数
     * JSON格式，存储支付所需的参数
     */
    @Schema(description = "支付参数（JSON格式）", 
            example = "{\"timeStamp\":\"1640995200\",\"nonceStr\":\"abc123\",\"package\":\"prepay_id=wx123\",\"signType\":\"MD5\",\"paySign\":\"sign123\"}")
    private String paymentParams;
    
    /**
     * 回调数据
     * JSON格式，存储支付平台回调的数据
     */
    @Schema(description = "回调数据（JSON格式）", 
            example = "{\"return_code\":\"SUCCESS\",\"result_code\":\"SUCCESS\",\"transaction_id\":\"wx123\"}")
    private String callbackData;
    
    /**
     * 订单状态
     * PENDING: 待支付
     * PAID: 已支付
     * CANCELLED: 已取消
     * EXPIRED: 已过期
     * REFUNDED: 已退款
     */
    @Schema(description = "订单状态", example = "PENDING",
            allowableValues = {"PENDING", "PAID", "CANCELLED", "EXPIRED", "REFUNDED"})
    private String status;
    
    /**
     * 支付完成时间
     */
    @Schema(description = "支付完成时间", example = "2026-01-26T10:35:00")
    private LocalDateTime paidTime;
    
    /**
     * 订单过期时间
     */
    @Schema(description = "订单过期时间", example = "2026-01-26T11:30:00")
    private LocalDateTime expireTime;
    
    /**
     * 支付结果通知地址
     */
    @Schema(description = "支付结果通知地址", 
            example = "https://api.example.com/payment/callback")
    private String notifyUrl;
    
    /**
     * 支付完成跳转地址
     */
    @Schema(description = "支付完成跳转地址", 
            example = "https://app.example.com/payment/success")
    private String returnUrl;

    /**
     * 扩展字段
     * JSON 格式，用于存储支付流程中的非核心业务数据，例如微信 openid。
     */
    @Schema(description = "扩展字段（JSON格式）", hidden = true)
    private String ext;
    
    /**
     * 备注信息
     */
    @Schema(description = "备注信息", example = "用户充值")
    private String remark;
    
    /**
     * 关联业务单号（通用，由调用方传入）
     */
    @Schema(description = "关联业务单号", example = "BIZ202601261234567890")
    private String bizNo;

    /**
     * 业务类型
     * 用于多租户隔离，标识该订单属于哪个业务系统
     */
    @Schema(description = "业务类型", example = "DRONE_ORDER")
    private String bizType;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间", example = "2026-01-26T10:30:00")
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    @Schema(description = "更新时间", example = "2026-01-26T10:30:00")
    private LocalDateTime updateTime;

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
     * 获取订单状态枚举
     */
    public PaymentOrderStatus getStatusEnum() {
        return PaymentOrderStatus.getByCode(status);
    }

    /**
     * 设置订单状态枚举
     */
    public void setStatusEnum(PaymentOrderStatus statusEnum) {
        if (statusEnum != null) {
            this.status = statusEnum.getCode();
        }
    }

    /**
     * 检查订单是否待支付
     * 
     * @return true表示待支付，false表示其他状态
     */
    public boolean isPending() {
        return PaymentOrderStatus.PENDING.getCode().equals(status);
    }
    
    /**
     * 检查订单是否已支付
     * 
     * @return true表示已支付，false表示其他状态
     */
    public boolean isPaid() {
        return PaymentOrderStatus.PAID.getCode().equals(status);
    }
    
    /**
     * 检查订单是否已取消
     * 
     * @return true表示已取消，false表示其他状态
     */
    public boolean isCancelled() {
        return PaymentOrderStatus.CANCELLED.getCode().equals(status);
    }
    
    /**
     * 检查订单是否已过期
     * 
     * @return true表示已过期，false表示其他状态
     */
    public boolean isExpired() {
        return PaymentOrderStatus.EXPIRED.getCode().equals(status) || 
               (expireTime != null && LocalDateTime.now().isAfter(expireTime));
    }
    
    /**
     * 检查订单是否已退款
     * 
     * @return true表示已退款，false表示其他状态
     */
    public boolean isRefunded() {
        return PaymentOrderStatus.REFUNDED.getCode().equals(status);
    }
    
    /**
     * 检查订单是否可以支付
     * 
     * @return true表示可以支付，false表示不可以支付
     */
    public boolean canPay() {
        return isPending() && !isExpired();
    }
    
    /**
     * 检查订单是否可以取消
     * 
     * @return true表示可以取消，false表示不可以取消
     */
    public boolean canCancel() {
        return isPending() && !isExpired();
    }
    
    /**
     * 检查订单是否可以退款
     *
     * @return true表示可以退款，false表示不可以退款
     */
    public boolean canRefund() {
        return isPaid() && !isRefunded();
    }

    // ==================== 状态转换领域方法 ====================

    /**
     * 标记为已支付
     *
     * @param callbackData 回调数据
     * @param transactionNo 第三方交易号
     * @throws IllegalStateException 如果当前状态不允许此转换
     */
    public void markAsPaid(String callbackData, String transactionNo) {
        PaymentOrderStatus current = getStatusEnum();
        PaymentOrderStatus target = PaymentOrderStatus.PAID;
        if (current == null || !current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "订单状态不允许从 " + (current != null ? current.getDescription() : "未知") + " 转换为已支付");
        }
        this.status = target.getCode();
        this.callbackData = callbackData;
        this.thirdPartyTransactionNo = transactionNo;
        this.paidTime = LocalDateTime.now();
    }

    /**
     * 取消订单
     *
     * @throws IllegalStateException 如果当前状态不允许此转换
     */
    public void cancel() {
        PaymentOrderStatus current = getStatusEnum();
        PaymentOrderStatus target = PaymentOrderStatus.CANCELLED;
        if (current == null || !current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "订单状态不允许从 " + (current != null ? current.getDescription() : "未知") + " 转换为已取消");
        }
        this.status = target.getCode();
    }

    /**
     * 标记订单过期
     *
     * @throws IllegalStateException 如果当前状态不允许此转换
     */
    public void expire() {
        PaymentOrderStatus current = getStatusEnum();
        PaymentOrderStatus target = PaymentOrderStatus.EXPIRED;
        if (current == null || !current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "订单状态不允许从 " + (current != null ? current.getDescription() : "未知") + " 转换为已过期");
        }
        this.status = target.getCode();
    }

    /**
     * 标记为已退款（全额）
     *
     * @throws IllegalStateException 如果当前状态不允许此转换
     */
    public void markAsRefunded() {
        PaymentOrderStatus current = getStatusEnum();
        PaymentOrderStatus target = PaymentOrderStatus.REFUNDED;
        if (current == null || !current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "订单状态不允许从 " + (current != null ? current.getDescription() : "未知") + " 转换为已退款");
        }
        this.status = target.getCode();
    }

    /**
     * 标记为部分退款
     *
     * @throws IllegalStateException 如果当前状态不允许此转换
     */
    public void markAsPartialRefunded() {
        PaymentOrderStatus current = getStatusEnum();
        PaymentOrderStatus target = PaymentOrderStatus.PARTIAL_REFUNDED;
        if (current == null || !current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "订单状态不允许从 " + (current != null ? current.getDescription() : "未知") + " 转换为部分退款");
        }
        this.status = target.getCode();
    }
    
    /**
     * 获取平台显示名称
     * 
     * @return 平台显示名称
     */
    public String getPlatformDisplayName() {
        if (platform == null) {
            return "未知";
        }

        return switch (platform) {
            case PaymentConstants.PLATFORM_WECHAT -> "微信支付";
            case PaymentConstants.PLATFORM_ALIPAY -> "支付宝";
            case PaymentConstants.PLATFORM_DOUYIN -> "抖音支付";
            default -> platform;
        };
    }
    
    public String getPaymentMethodDisplayName() {
        if (paymentMethod == null) {
            return "未知";
        }

        return switch (paymentMethod) {
            case PaymentConstants.METHOD_WECHAT_APP -> "微信 App 支付";
            case PaymentConstants.METHOD_WECHAT_JSAPI -> "微信 JSAPI / 小程序";
            case PaymentConstants.METHOD_ALIPAY_APP -> "支付宝 App 支付";
            case PaymentConstants.METHOD_ALIPAY_JSAPI -> "支付宝 JSAPI / 小程序";
            case PaymentConstants.METHOD_DOUYIN_APP -> "抖音 App 支付";
            case PaymentConstants.METHOD_DOUYIN_MINI -> "抖音小程序";
            default -> paymentMethod;
        };
    }
    
    /**
     * 获取订单状态显示名称
     * 
     * @return 订单状态显示名称
     */
    public String getStatusDisplayName() {
        PaymentOrderStatus statusEnum = getStatusEnum();
        return statusEnum != null ? statusEnum.getDescription() : "未知";
    }
}
