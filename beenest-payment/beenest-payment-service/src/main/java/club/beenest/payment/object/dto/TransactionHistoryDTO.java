package club.beenest.payment.object.dto;

import club.beenest.payment.constant.PaymentConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 交易历史记录DTO
 * 用于返回用户交易历史信息
 * 
 * <p>包含交易的详细信息，用于前端展示交易列表</p>
 * 
 * @author System
 * @since 2026-01-26
 */
@Schema(name = "TransactionHistoryDTO", description = "交易历史记录")
@Data
public class TransactionHistoryDTO {
    
    /**
     * 交易流水号
     */
    @Schema(description = "交易流水号", example = "T202601261234567890123")
    private String transactionNo;
    
    /**
     * 交易类型
     */
    @Schema(description = "交易类型", example = "RECHARGE")
    private String transactionType;
    
    /**
     * 交易类型显示名称
     */
    @Schema(description = "交易类型显示名称", example = "充值")
    private String transactionTypeDisplayName;
    
    /**
     * 交易金额（分）
     * 正数表示收入，负数表示支出
     */
    @Schema(description = "交易金额（分）", example = "10000")
    private Long amount;
    
    /**
     * 交易描述
     */
    @Schema(description = "交易描述", example = "微信小程序充值")
    private String description;
    
    /**
     * 交易状态
     */
    @Schema(description = "交易状态", example = "SUCCESS")
    private String status;
    
    /**
     * 交易状态显示名称
     */
    @Schema(description = "交易状态显示名称", example = "成功")
    private String statusDisplayName;
    
    /**
     * 关联单号
     */
    @Schema(description = "关联单号", example = "P202601261234567890123")
    private String referenceNo;
    
    /**
     * 关联类型
     */
    @Schema(description = "关联类型", example = "PAYMENT_ORDER")
    private String referenceType;
    
    /**
     * 交易时间
     */
    @Schema(description = "交易时间", example = "2026-01-26T10:30:00")
    private LocalDateTime createTime;
    
    /**
     * 备注信息
     */
    @Schema(description = "备注信息", example = "系统自动处理")
    private String remark;

    /**
     * 客户姓名
     */
    @Schema(description = "客户姓名")
    private String customerName;

    /**
     * 客户手机号
     */
    @Schema(description = "客户手机号")
    private String customerPhone;

    /**
     * 客户编号
     */
    @Schema(description = "客户编号")
    private String customerNo;
    
    // ==================== 业务方法 ====================
    
    /**
     * 获取交易金额（元）
     * 将分转换为元，保留两位小数
     * 
     * @return 交易金额（元）
     */
    public BigDecimal getAmountInYuan() {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(amount).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }
    
    /**
     * 检查是否为收入交易
     * 
     * @return true表示收入，false表示支出
     */
    public boolean isIncome() {
        return amount != null && amount > 0;
    }
    
    /**
     * 检查是否为支出交易
     * 
     * @return true表示支出，false表示收入
     */
    public boolean isExpense() {
        return amount != null && amount < 0;
    }
    
    /**
     * 检查交易是否成功
     * 
     * @return true表示成功，false表示未成功
     */
    public boolean isSuccess() {
        return PaymentConstants.REFUND_STATUS_SUCCESS.equals(status);
    }
    
    /**
     * 获取交易金额的绝对值（元）
     * 用于前端显示，不显示负号
     * 
     * @return 交易金额的绝对值（元）
     */
    public BigDecimal getAbsAmountInYuan() {
        return getAmountInYuan().abs();
    }
    
    /**
     * 获取交易类型图标
     * 根据交易类型返回对应的图标类名
     * 
     * @return 图标类名
     */
    public String getTransactionIcon() {
        if (transactionType == null) {
            return "icon-unknown";
        }

        return switch (transactionType) {
            case PaymentConstants.TRANSACTION_TYPE_RECHARGE -> "icon-charge";
            case PaymentConstants.TRANSACTION_TYPE_WITHDRAW -> "icon-withdraw";
            case PaymentConstants.TRANSACTION_TYPE_PAYMENT -> "icon-pay";
            case PaymentConstants.TRANSACTION_TYPE_REFUND -> "icon-refund";
            case PaymentConstants.TRANSACTION_TYPE_RED_PACKET_CONVERT -> "icon-red-packet";
            case PaymentConstants.TRANSACTION_TYPE_FEE -> "icon-fee";
            case PaymentConstants.TRANSACTION_TYPE_PENALTY -> "icon-penalty";
            default -> "icon-transaction";
        };
    }
    
    /**
     * 获取交易类型颜色
     * 根据交易类型返回对应的颜色类名
     * 
     * @return 颜色类名
     */
    public String getTransactionColor() {
        if (isIncome()) {
            return "text-success"; // 绿色，表示收入
        } else if (isExpense()) {
            return "text-danger"; // 红色，表示支出
        } else {
            return "text-muted"; // 灰色，表示其他
        }
    }
    
    /**
     * 获取格式化的交易时间
     * 
     * @return 格式化的交易时间字符串
     */
    public String getFormattedCreateTime() {
        if (createTime == null) {
            return "";
        }
        
        // 格式：MM-dd HH:mm
        return String.format("%02d-%02d %02d:%02d",
                createTime.getMonthValue(),
                createTime.getDayOfMonth(),
                createTime.getHour(),
                createTime.getMinute());
    }
}