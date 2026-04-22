package club.beenest.payment.wallet.domain.entity;

import club.beenest.payment.wallet.domain.enums.WalletStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 用户钱包实体类
 * 对应数据库表：ds_wallet
 * 
 * <p>用于存储用户钱包基本信息和余额数据</p>
 * 
 * <h3>主要功能：</h3>
 * <ul>
 *   <li>余额管理 - 可用余额、冻结余额</li>
 *   <li>统计信息 - 累计充值、提现、消费</li>
 *   <li>状态控制 - 钱包状态管理</li>
 *   <li>并发控制 - 版本号乐观锁</li>
 * </ul>
 * 
 * <h3>安全特性：</h3>
 * <ul>
 *   <li>金额以分为单位存储，避免浮点数精度问题</li>
 *   <li>版本号控制，防止并发更新冲突</li>
 *   <li>状态字段控制钱包可用性</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-26
 */
@Schema(name = "Wallet", description = "用户钱包实体")
@Data
public class Wallet {
    
    /**
     * 主键ID
     */
    @Schema(description = "主键ID", example = "1")
    private Long id;
    
    /**
     * 钱包编号
     * 唯一标识，格式：W + 时间戳 + 随机数
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
     * 用于多租户隔离，同一用户不同业务类型拥有独立钱包
     */
    @Schema(description = "业务类型", example = "DRONE_ORDER")
    private String bizType;

    /**
     * 可用余额
     * 单位：分，避免浮点数精度问题
     */
    @Schema(description = "可用余额（分）", example = "10000")
    private Long balance;
    
    /**
     * 冻结余额
     * 单位：分，用于处理中的交易
     */
    @Schema(description = "冻结余额（分）", example = "0")
    private Long frozenBalance;
    
    /**
     * 累计充值金额
     * 单位：分，统计用户历史充值总额
     */
    @Schema(description = "累计充值金额（分）", example = "50000")
    private Long totalRecharge;
    
    /**
     * 累计提现金额
     * 单位：分，统计用户历史提现总额
     */
    @Schema(description = "累计提现金额（分）", example = "20000")
    private Long totalWithdraw;
    
    /**
     * 累计消费金额
     * 单位：分，统计用户历史消费总额
     */
    @Schema(description = "累计消费金额（分）", example = "30000")
    private Long totalConsume;
    
    /**
     * 钱包状态
     * ACTIVE: 正常状态，可以进行所有操作
     * FROZEN: 冻结状态，只能查询，不能进行资金操作
     * CLOSED: 关闭状态，钱包已关闭
     */
    @Schema(description = "钱包状态", example = "ACTIVE", 
            allowableValues = {"ACTIVE", "FROZEN", "CLOSED"})
    private String status;
    
    /**
     * 版本号
     * 用于乐观锁控制，防止并发更新冲突
     */
    @Schema(description = "版本号", example = "1")
    private Integer version;

    /**
     * 余额哈希校验值
     * SHA256(balance|frozenBalance|version|walletNo)
     * 用于检测余额是否被直接篡改
     */
    @Schema(description = "余额哈希校验值", hidden = true)
    private String balanceHash;
    
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
     * 获取可用余额（元）
     * 将分转换为元，保留两位小数
     * 
     * @return 可用余额（元）
     */
    public BigDecimal getBalanceInYuan() {
        if (balance == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(balance).divide(new BigDecimal(100), 2, java.math.RoundingMode.HALF_UP);
    }
    
    /**
     * 获取冻结余额（元）
     * 将分转换为元，保留两位小数
     * 
     * @return 冻结余额（元）
     */
    public BigDecimal getFrozenBalanceInYuan() {
        if (frozenBalance == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(frozenBalance).divide(new BigDecimal(100), 2, java.math.RoundingMode.HALF_UP);
    }
    
    /**
     * 获取总余额（元）
     * 可用余额 + 冻结余额
     * 
     * @return 总余额（元）
     */
    public BigDecimal getTotalBalanceInYuan() {
        return getBalanceInYuan().add(getFrozenBalanceInYuan());
    }
    
    /**
     * 获取累计充值金额（元）
     * 
     * @return 累计充值金额（元）
     */
    public BigDecimal getTotalRechargeInYuan() {
        if (totalRecharge == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(totalRecharge).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }
    
    /**
     * 获取累计提现金额（元）
     * 
     * @return 累计提现金额（元）
     */
    public BigDecimal getTotalWithdrawInYuan() {
        if (totalWithdraw == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(totalWithdraw).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }
    
    /**
     * 获取累计消费金额（元）
     * 
     * @return 累计消费金额（元）
     */
    public BigDecimal getTotalConsumeInYuan() {
        if (totalConsume == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(totalConsume).divide(new BigDecimal(100), 2, java.math.RoundingMode.HALF_UP);
    }
    
    /**
     * 检查钱包是否可用
     * 
     * @return true表示可用，false表示不可用
     */
    public boolean isActive() {
        return WalletStatus.ACTIVE.getCode().equals(status);
    }
    
    /**
     * 检查钱包是否被冻结
     * 
     * @return true表示被冻结，false表示未冻结
     */
    public boolean isFrozen() {
        return WalletStatus.FROZEN.getCode().equals(status);
    }
    
    /**
     * 检查钱包是否已关闭
     * 
     * @return true表示已关闭，false表示未关闭
     */
    public boolean isClosed() {
        return WalletStatus.CLOSED.getCode().equals(status);
    }
    
    /**
     * 检查余额是否充足
     * 
     * @param amount 需要检查的金额（分）
     * @return true表示余额充足，false表示余额不足
     */
    public boolean hasEnoughBalance(Long amount) {
        if (amount == null || amount <= 0) {
            return true;
        }
        return balance != null && balance >= amount;
    }
    
    /**
     * 检查余额是否充足（元）
     * 
     * @param amount 需要检查的金额（元）
     * @return true表示余额充足，false表示余额不足
     */
    public boolean hasEnoughBalance(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        Long amountInCents = amount.multiply(new BigDecimal(100)).longValue();
        return hasEnoughBalance(amountInCents);
    }

    /**
     * 检查冻结余额是否充足
     */
    public boolean hasEnoughFrozenBalance(Long amount) {
        if (amount == null || amount <= 0) {
            return true;
        }
        return frozenBalance != null && frozenBalance >= amount;
    }

    /**
     * 校验是否可以冻结指定金额
     */
    public boolean canFreeze(Long amountInCents) {
        return isActive() && hasEnoughBalance(amountInCents);
    }

    /**
     * 校验是否可以解冻指定金额
     */
    public boolean canUnfreeze(Long amountInCents) {
        return isActive() && hasEnoughFrozenBalance(amountInCents);
    }
}