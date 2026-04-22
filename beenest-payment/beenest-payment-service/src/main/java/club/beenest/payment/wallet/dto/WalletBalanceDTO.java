package club.beenest.payment.wallet.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 钱包余额信息DTO
 * 用于返回用户钱包余额相关信息
 * 
 * <p>包含用户钱包的完整余额信息，包括可用余额、红包余额、优惠券数量等</p>
 * 
 * @author System
 * @since 2026-01-26
 */
@Schema(name = "WalletBalanceDTO", description = "钱包余额信息")
@Data
public class WalletBalanceDTO {
    
    /**
     * 可用余额（分）
     * 用户可以直接使用的余额
     */
    @Schema(description = "可用余额（分）", example = "10000")
    private Long balance;
    
    /**
     * 冻结余额（分）
     * 处理中的交易冻结的余额
     */
    @Schema(description = "冻结余额（分）", example = "0")
    private Long frozenBalance;
    
    /**
     * 红包余额（分）
     * 用户红包的总余额
     */
    @Schema(description = "红包余额（分）", example = "2000")
    private Long redPacketBalance;
    
    /**
     * 可用优惠券数量
     */
    @Schema(description = "可用优惠券数量", example = "5")
    private Integer couponCount;
    
    /**
     * 累计充值金额（分）
     */
    @Schema(description = "累计充值金额（分）", example = "50000")
    private Long totalRecharge;
    
    /**
     * 累计提现金额（分）
     */
    @Schema(description = "累计提现金额（分）", example = "20000")
    private Long totalWithdraw;
    
    /**
     * 累计消费金额（分）
     */
    @Schema(description = "累计消费金额（分）", example = "30000")
    private Long totalConsume;
    
    // ==================== 业务方法 ====================
    
    /**
     * 获取可用余额（元）
     * 
     * @return 可用余额（元）
     */
    public BigDecimal getBalanceInYuan() {
        if (balance == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(balance).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }
    
    /**
     * 获取冻结余额（元）
     * 
     * @return 冻结余额（元）
     */
    public BigDecimal getFrozenBalanceInYuan() {
        if (frozenBalance == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(frozenBalance).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }
    
    /**
     * 获取红包余额（元）
     * 
     * @return 红包余额（元）
     */
    public BigDecimal getRedPacketBalanceInYuan() {
        if (redPacketBalance == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(redPacketBalance).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
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
        return new BigDecimal(totalConsume).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }
}