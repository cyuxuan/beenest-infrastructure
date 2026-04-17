package club.beenest.payment.object.entity;

import club.beenest.payment.object.enums.WithdrawStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 提现申请实体类
 * 对应数据库表：ds_withdraw_request
 * 
 * <p>记录用户提现申请和处理状态</p>
 * 
 * <h3>主要功能：</h3>
 * <ul>
 *   <li>提现申请 - 用户发起提现申请</li>
 *   <li>审核流程 - 管理员审核提现申请</li>
 *   <li>处理跟踪 - 跟踪提现处理状态</li>
 *   <li>账户管理 - 管理提现账户信息</li>
 * </ul>
 * 
 * <h3>提现类型：</h3>
 * <ul>
 *   <li>ALIPAY - 支付宝提现</li>
 *   <li>BANK_CARD - 银行卡提现</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-26
 */
@Schema(name = "WithdrawRequest", description = "提现申请实体")
@Data
public class WithdrawRequest {
    
    /**
     * 主键ID
     */
    @Schema(description = "主键ID", example = "1")
    private Long id;
    
    /**
     * 提现申请号
     * 唯一标识，格式：WR + 时间戳 + 随机数
     */
    @Schema(description = "提现申请号", example = "WR202601261234567890123")
    private String requestNo;
    
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
     * 钱包编号
     * 关联钱包表的wallet_no字段
     */
    @Schema(description = "钱包编号", example = "W202601261234567890123")
    private String walletNo;
    
    /**
     * 提现金额
     * 单位：分
     */
    @Schema(description = "提现金额（分）", example = "10000")
    private Long amount;
    
    /**
     * 提现类型
     * ALIPAY: 支付宝提现
     * BANK_CARD: 银行卡提现
     */
    @Schema(description = "提现类型", example = "ALIPAY",
            allowableValues = {"ALIPAY", "BANK_CARD"})
    private String withdrawType;
    
    /**
     * 账户类型
     * PERSONAL: 个人账户
     * COMPANY: 企业账户
     */
    @Schema(description = "账户类型", example = "PERSONAL",
            allowableValues = {"PERSONAL", "COMPANY"})
    private String accountType;
    
    /**
     * 账户姓名/企业名称
     */
    @Schema(description = "账户姓名/企业名称", example = "张三")
    private String accountName;
    
    /**
     * 账户号码
     * 支付宝账号或银行卡号
     */
    @Schema(description = "账户号码", example = "13800138000")
    private String accountNumber;
    
    /**
     * 银行名称
     * 银行卡提现时必填
     */
    @Schema(description = "银行名称", example = "中国工商银行")
    private String bankName;
    
    /**
     * 开户行支行
     * 银行卡提现时选填
     */
    @Schema(description = "开户行支行", example = "北京分行营业部")
    private String bankBranch;
    
    /**
     * 手续费金额
     * 单位：分
     */
    @Schema(description = "手续费金额（分）", example = "200")
    private Long feeAmount;
    
    /**
     * 实际到账金额
     * 单位：分，提现金额 - 手续费
     */
    @Schema(description = "实际到账金额（分）", example = "9800")
    private Long actualAmount;
    
    /**
     * 申请状态
     * PENDING: 待审核
     * APPROVED: 已审核
     * PROCESSING: 处理中
     * SUCCESS: 成功
     * FAILED: 失败
     * CANCELLED: 已取消
     */
    @Schema(description = "申请状态", example = "PENDING",
            allowableValues = {"PENDING", "APPROVED", "PROCESSING", 
                             "SUCCESS", "FAILED", "CANCELLED"})
    private String status;
    
    /**
     * 审核人
     */
    @Schema(description = "审核人", example = "admin")
    private String auditUser;
    
    /**
     * 审核时间
     */
    @Schema(description = "审核时间", example = "2026-01-26T11:00:00")
    private LocalDateTime auditTime;
    
    /**
     * 审核备注
     */
    @Schema(description = "审核备注", example = "审核通过")
    private String auditRemark;
    
    /**
     * 处理完成时间
     */
    @Schema(description = "处理完成时间", example = "2026-01-26T12:00:00")
    private LocalDateTime processTime;
    
    /**
     * 处理结果
     */
    @Schema(description = "处理结果", example = "提现成功")
    private String processResult;
    
    /**
     * 第三方订单号
     * 支付平台返回的提现订单号
     */
    @Schema(description = "第三方订单号", example = "alipay_withdraw_123456789")
    private String thirdPartyOrderNo;
    
    /**
     * 第三方交易号
     * 支付平台返回的交易号（别名，与thirdPartyOrderNo相同）
     */
    @Schema(description = "第三方交易号", example = "alipay_withdraw_123456789")
    private String thirdPartyTransactionNo;
    
    /**
     * 失败原因
     */
    @Schema(description = "失败原因", example = "账户信息错误")
    private String failReason;
    
    /**
     * 取消原因
     */
    @Schema(description = "取消原因", example = "用户主动取消")
    private String cancelReason;
    
    /**
     * 取消时间
     */
    @Schema(description = "取消时间", example = "2026-01-26T13:00:00")
    private LocalDateTime cancelTime;
    
    /**
     * 提现平台
     * WECHAT: 微信零钱
     * ALIPAY: 支付宝
     * DOUYIN: 抖音
     */
    @Schema(description = "提现平台", example = "WECHAT",
            allowableValues = {"WECHAT", "ALIPAY", "DOUYIN"})
    private String platform;
    
    /**
     * 账户信息
     * 微信openid、支付宝账号等
     */
    @Schema(description = "账户信息", example = "oUpF8uMuAJO_M2pxb1Q9zNjWeS6o")
    private String accountInfo;
    
    /**
     * 备注信息
     */
    @Schema(description = "备注信息", example = "用户提现申请")
    private String remark;

    /**
     * 业务类型
     * 用于多租户隔离
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
     * 获取提现金额（元）
     * 将分转换为元，保留两位小数
     * 
     * @return 提现金额（元）
     */
    public BigDecimal getAmountInYuan() {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(amount).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }
    
    /**
     * 获取手续费金额（元）
     * 
     * @return 手续费金额（元）
     */
    public BigDecimal getFeeAmountInYuan() {
        if (feeAmount == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(feeAmount).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }
    
    /**
     * 获取实际到账金额（元）
     * 
     * @return 实际到账金额（元）
     */
    public BigDecimal getActualAmountInYuan() {
        if (actualAmount == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(actualAmount).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }
    
    /**
     * 获取状态枚举
     */
    public WithdrawStatus getStatusEnum() {
        return WithdrawStatus.getByCode(status);
    }

    /**
     * 设置状态枚举
     */
    public void setStatusEnum(WithdrawStatus statusEnum) {
        if (statusEnum != null) {
            this.status = statusEnum.getCode();
        }
    }

    /**
     * 检查是否待审核
     * 
     * @return true表示待审核，false表示其他状态
     */
    public boolean isPending() {
        return WithdrawStatus.PENDING.getCode().equals(status);
    }
    
    /**
     * 检查是否已审核
     * 
     * @return true表示已审核，false表示其他状态
     */
    public boolean isApproved() {
        return WithdrawStatus.APPROVED.getCode().equals(status);
    }
    
    /**
     * 检查是否处理中
     * 
     * @return true表示处理中，false表示其他状态
     */
    public boolean isProcessing() {
        return WithdrawStatus.PROCESSING.getCode().equals(status);
    }
    
    /**
     * 检查是否成功
     * 
     * @return true表示成功，false表示其他状态
     */
    public boolean isSuccess() {
        return WithdrawStatus.SUCCESS.getCode().equals(status);
    }
    
    /**
     * 检查是否失败
     * 
     * @return true表示失败，false表示其他状态
     */
    public boolean isFailed() {
        return WithdrawStatus.FAILED.getCode().equals(status);
    }
    
    /**
     * 检查是否已取消
     * 
     * @return true表示已取消，false表示其他状态
     */
    public boolean isCancelled() {
        return WithdrawStatus.CANCELLED.getCode().equals(status);
    }
    
    /**
     * 检查是否可以审核
     * 
     * @return true表示可以审核，false表示不可以审核
     */
    public boolean canAudit() {
        return isPending();
    }
    
    /**
     * 检查是否可以取消
     * 
     * @return true表示可以取消，false表示不可以取消
     */
    public boolean canCancel() {
        return isPending() || isApproved();
    }
    
    /**
     * 检查是否可以处理
     * 
     * @return true表示可以处理，false表示不可以处理
     */
    public boolean canProcess() {
        return isApproved();
    }
    
    /**
     * 获取提现类型显示名称
     * 
     * @return 提现类型显示名称
     */
    public String getWithdrawTypeDisplayName() {
        if (withdrawType == null) {
            return "未知";
        }

        return switch (withdrawType) {
            case "ALIPAY" -> "支付宝";
            case "BANK_CARD" -> "银行卡";
            default -> withdrawType;
        };
    }
    
    /**
     * 获取账户类型显示名称
     * 
     * @return 账户类型显示名称
     */
    public String getAccountTypeDisplayName() {
        if (accountType == null) {
            return "未知";
        }

        return switch (accountType) {
            case "PERSONAL" -> "个人";
            case "COMPANY" -> "企业";
            default -> accountType;
        };
    }
    
    /**
     * 获取申请状态显示名称
     * 
     * @return 申请状态显示名称
     */
    public String getStatusDisplayName() {
        WithdrawStatus statusEnum = getStatusEnum();
        return statusEnum != null ? statusEnum.getDescription() : "未知";
    }
    
    /**
     * 获取脱敏后的账户号码
     * 隐藏中间部分，保护用户隐私
     * 
     * @return 脱敏后的账户号码
     */
    public String getMaskedAccountNumber() {
        if (accountNumber == null || accountNumber.length() < 4) {
            return accountNumber;
        }
        
        int length = accountNumber.length();
        if (length <= 8) {
            // 短号码，显示前2位和后2位
            return accountNumber.substring(0, 2) + "****" + 
                   accountNumber.substring(length - 2);
        } else {
            // 长号码，显示前4位和后4位
            return accountNumber.substring(0, 4) + "****" + 
                   accountNumber.substring(length - 4);
        }
    }
}