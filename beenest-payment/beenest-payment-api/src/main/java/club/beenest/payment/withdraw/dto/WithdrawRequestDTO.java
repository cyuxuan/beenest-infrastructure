package club.beenest.payment.withdraw.dto;

import club.beenest.payment.shared.constant.PaymentConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 提现请求DTO
 * 用于接收用户提现请求参数
 * 
 * <p>包含提现所需的完整信息，如金额、提现类型、账户信息等</p>
 * 
 * @author System
 * @since 2026-01-26
 */
@Schema(name = "WithdrawRequestDTO", description = "提现请求参数")
@Data
public class WithdrawRequestDTO {
    
    /**
     * 提现金额（分）
     * 最小值为10000分（100元）
     */
    @Schema(description = "提现金额（分）", example = "10000", minimum = "10000")
    @NotNull(message = "提现金额不能为空")
    @Min(value = 10000, message = "提现金额不能少于100元")
    private Long amount;
    
    /**
     * 提现类型
     * ALIPAY: 支付宝提现
     * BANK_CARD: 银行卡提现
     * WECHAT: 微信零钱
     * DOUYIN: 抖音
     */
    @Schema(description = "提现类型", example = "ALIPAY", 
            allowableValues = {"ALIPAY", "BANK_CARD"})
    @NotBlank(message = "提现类型不能为空")
    private String withdrawType;
    
    /**
     * 账户类型
     * PERSONAL: 个人账户
     * COMPANY: 企业账户
     */
    @Schema(description = "账户类型", example = "PERSONAL", 
            allowableValues = {"PERSONAL", "COMPANY"})
    @NotBlank(message = "账户类型不能为空")
    private String accountType;
    
    /**
     * 账户姓名/企业名称
     */
    @Schema(description = "账户姓名/企业名称", example = "张三")
    @NotBlank(message = "账户姓名不能为空")
    private String accountName;
    
    /**
     * 账户号码
     * 支付宝账号或银行卡号
     */
    @Schema(description = "账户号码", example = "13800138000")
    @NotBlank(message = "账户号码不能为空")
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
     * 备注信息（可选）
     */
    @Schema(description = "备注信息", example = "用户提现申请")
    private String remark;
    
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
    
    public boolean isValidWithdrawType() {
        if (withdrawType == null) {
            return false;
        }
        return PaymentConstants.WITHDRAW_TYPE_ALIPAY.equals(withdrawType) || PaymentConstants.WITHDRAW_TYPE_BANK_CARD.equals(withdrawType);
    }
    
    public boolean isValidAccountType() {
        if (accountType == null) {
            return false;
        }
        return PaymentConstants.ACCOUNT_TYPE_PERSONAL.equals(accountType) || PaymentConstants.ACCOUNT_TYPE_COMPANY.equals(accountType);
    }
    
    /**
     * 验证提现金额是否在合理范围内
     * 
     * @return true表示合理，false表示不合理
     */
    public boolean isValidAmount() {
        if (amount == null) {
            return false;
        }
        
        // 最小提现金额：100元（10000分）
        // 最大提现金额：5万元（5000000分）
        return amount >= 10000 && amount <= 5000000;
    }
    
    public boolean isBankCardWithdraw() {
        return PaymentConstants.WITHDRAW_TYPE_BANK_CARD.equals(withdrawType);
    }
    
    public boolean isAlipayWithdraw() {
        return PaymentConstants.WITHDRAW_TYPE_ALIPAY.equals(withdrawType);
    }
    
    /**
     * 验证银行卡提现必填字段
     * 
     * @return true表示验证通过，false表示验证失败
     */
    public boolean validateBankCardFields() {
        if (!isBankCardWithdraw()) {
            return true; // 非银行卡提现，不需要验证
        }
        
        // 银行卡提现时，银行名称必填
        return bankName != null && !bankName.trim().isEmpty();
    }
    
    /**
     * 验证账户号码格式
     * 
     * @return true表示格式正确，false表示格式错误
     */
    public boolean isValidAccountNumber() {
        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            return false;
        }
        
        String number = accountNumber.trim();
        
        if (isAlipayWithdraw()) {
            // 支付宝账号：手机号或邮箱
            return isValidPhone(number) || isValidEmail(number);
        } else if (isBankCardWithdraw()) {
            // 银行卡号：16-19位数字
            return number.matches("\\d{16,19}");
        }
        
        return true;
    }
    
    /**
     * 验证手机号格式
     * 
     * @param phone 手机号
     * @return true表示格式正确，false表示格式错误
     */
    private boolean isValidPhone(String phone) {
        if (phone == null) {
            return false;
        }
        return phone.matches("^1[3-9]\\d{9}$");
    }
    
    /**
     * 验证邮箱格式
     * 
     * @param email 邮箱
     * @return true表示格式正确，false表示格式错误
     */
    private boolean isValidEmail(String email) {
        if (email == null) {
            return false;
        }
        return email.matches("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
    }
    
    /**
     * 计算手续费金额（分）
     * 根据提现金额和提现类型计算手续费
     * 
     * @return 手续费金额（分）
     */
    public Long calculateFeeAmount() {
        if (amount == null) {
            return 0L;
        }
        
        // 手续费规则（可以根据实际业务调整）
        if (isAlipayWithdraw()) {
            // 支付宝提现：2元手续费
            return 200L;
        } else if (isBankCardWithdraw()) {
            // 银行卡提现：5元手续费
            return 500L;
        }
        
        return 0L;
    }
    
    /**
     * 计算实际到账金额（分）
     * 提现金额 - 手续费
     * 
     * @return 实际到账金额（分）
     */
    public Long calculateActualAmount() {
        if (amount == null) {
            return 0L;
        }
        
        Long feeAmount = calculateFeeAmount();
        return amount - feeAmount;
    }
}