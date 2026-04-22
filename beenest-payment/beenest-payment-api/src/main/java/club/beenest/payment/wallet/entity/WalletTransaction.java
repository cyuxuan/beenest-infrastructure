package club.beenest.payment.wallet.entity;

import club.beenest.payment.wallet.enums.WalletTransactionStatus;
import club.beenest.payment.wallet.enums.WalletTransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 钱包交易流水实体类
 * 对应数据库表：ds_wallet_transaction
 * 
 * <p>记录所有钱包资金变动的详细信息</p>
 * 
 * <h3>主要功能：</h3>
 * <ul>
 *   <li>交易记录 - 记录每笔资金变动</li>
 *   <li>余额追踪 - 记录交易前后余额</li>
 *   <li>关联追踪 - 关联订单、红包等业务</li>
 *   <li>审计支持 - 提供完整的资金流水</li>
 * </ul>
 * 
 * <h3>交易类型：</h3>
 * <ul>
 *   <li>RECHARGE - 充值</li>
 *   <li>WITHDRAW - 提现</li>
 *   <li>PAYMENT - 支付</li>
 *   <li>REFUND - 退款</li>
 *   <li>RED_PACKET_CONVERT - 红包兑换</li>
 *   <li>FEE - 手续费</li>
 *   <li>PENALTY - 违约金</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-26
 */
@Schema(name = "WalletTransaction", description = "钱包交易流水实体")
@Data
public class WalletTransaction {
    
    /**
     * 主键ID
     */
    @Schema(description = "主键ID", example = "1")
    private Long id;
    
    /**
     * 交易流水号
     * 唯一标识，格式：T + 时间戳 + 随机数
     */
    @Schema(description = "交易流水号", example = "T202601261234567890123")
    private String transactionNo;
    
    /**
     * 钱包编号
     * 关联钱包表的wallet_no字段
     */
    @Schema(description = "钱包编号", example = "W202601261234567890123")
    private String walletNo;
    
    /**
     * 用户编号
     * 关联用户表的customer_no字段
     */
    @Schema(description = "用户编号", example = "C202601261234567890123")
    private String customerNo;

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
     * 业务类型
     * 用于多租户隔离
     */
    @Schema(description = "业务类型", example = "DRONE_ORDER")
    private String bizType;

    /**
     * 交易类型
     * RECHARGE: 充值
     * WITHDRAW: 提现
     * PAYMENT: 支付
     * REFUND: 退款
     * RED_PACKET_CONVERT: 红包兑换
     * FEE: 手续费
     * PENALTY: 违约金
     */
    @Schema(description = "交易类型", example = "RECHARGE",
            allowableValues = {"RECHARGE", "WITHDRAW", "PAYMENT", "REFUND", 
                             "RED_PACKET_CONVERT", "FEE", "PENALTY"})
    private String transactionType;
    
    /**
     * 交易金额
     * 单位：分，正数表示收入，负数表示支出
     */
    @Schema(description = "交易金额（分）", example = "10000")
    private Long amount;
    
    /**
     * 交易前余额
     * 单位：分
     */
    @Schema(description = "交易前余额（分）", example = "50000")
    private Long beforeBalance;
    
    /**
     * 交易后余额
     * 单位：分
     */
    @Schema(description = "交易后余额（分）", example = "60000")
    private Long afterBalance;
    
    /**
     * 交易描述
     * 描述本次交易的具体内容
     */
    @Schema(description = "交易描述", example = "微信小程序充值")
    private String description;
    
    /**
     * 关联单号
     * 如订单号、红包编号、优惠券编号等
     */
    @Schema(description = "关联单号", example = "O202601261234567890123")
    private String referenceNo;
    
    /**
     * 关联类型
     * ORDER: 订单
     * RED_PACKET: 红包
     * COUPON: 优惠券
     * WITHDRAW_REQUEST: 提现申请
     * PAYMENT_ORDER: 充值订单
     */
    @Schema(description = "关联类型", example = "PAYMENT_ORDER",
            allowableValues = {"ORDER", "RED_PACKET", "COUPON", 
                             "WITHDRAW_REQUEST", "PAYMENT_ORDER"})
    private String referenceType;
    
    /**
     * 交易状态
     * SUCCESS: 成功
     * FAILED: 失败
     * PROCESSING: 处理中
     */
    @Schema(description = "交易状态", example = "SUCCESS",
            allowableValues = {"SUCCESS", "FAILED", "PROCESSING"})
    private String status;
    
    /**
     * 备注信息
     * 额外的说明信息
     */
    @Schema(description = "备注信息", example = "系统自动处理")
    private String remark;
    
    /**
     * 创建时间
     */
    @Schema(description = "创建时间", example = "2026-01-26T10:30:00")
    private LocalDateTime createTime;
    
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
     * 获取交易前余额（元）
     * 
     * @return 交易前余额（元）
     */
    public BigDecimal getBeforeBalanceInYuan() {
        if (beforeBalance == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(beforeBalance).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }
    
    /**
     * 获取交易后余额（元）
     * 
     * @return 交易后余额（元）
     */
    public BigDecimal getAfterBalanceInYuan() {
        if (afterBalance == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(afterBalance).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
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
        return WalletTransactionStatus.SUCCESS.getCode().equals(status);
    }
    
    /**
     * 检查交易是否失败
     * 
     * @return true表示失败，false表示未失败
     */
    public boolean isFailed() {
        return WalletTransactionStatus.FAILED.getCode().equals(status);
    }
    
    /**
     * 检查交易是否处理中
     * 
     * @return true表示处理中，false表示已完成
     */
    public boolean isProcessing() {
        return WalletTransactionStatus.PROCESSING.getCode().equals(status);
    }
    
    /**
     * 获取交易类型显示名称
     * 
     * @return 交易类型显示名称
     */
    public String getTransactionTypeDisplayName() {
        if (transactionType == null) {
            return "未知";
        }

        for (WalletTransactionType type : WalletTransactionType.values()) {
            if (type.getCode().equals(transactionType)) {
                return type.getDescription();
            }
        }
        return transactionType;
    }
    
    /**
     * 获取交易状态显示名称
     * 
     * @return 交易状态显示名称
     */
    public String getStatusDisplayName() {
        if (status == null) {
            return "未知";
        }

        for (WalletTransactionStatus s : WalletTransactionStatus.values()) {
            if (s.getCode().equals(status)) {
                return s.getDescription();
            }
        }
        return status;
    }
}