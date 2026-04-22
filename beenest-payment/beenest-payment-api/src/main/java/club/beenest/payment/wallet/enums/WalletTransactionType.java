package club.beenest.payment.wallet.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 钱包交易类型枚举
 * 
 * @author System
 * @since 2026-01-28
 */
@Getter
@AllArgsConstructor
public enum WalletTransactionType {
    RECHARGE("RECHARGE", "充值"),
    WITHDRAW("WITHDRAW", "提现"),
    PAYMENT("PAYMENT", "支付"),
    REFUND("REFUND", "退款"),
    RED_PACKET_CONVERT("RED_PACKET_CONVERT", "红包兑换"),
    FEE("FEE", "手续费"),
    PENALTY("PENALTY", "违约金"),
    PILOT_INCOME("PILOT_INCOME", "飞手收入"),
    PLATFORM_FEE("PLATFORM_FEE", "平台服务费");

    private final String code;
    private final String description;

    public static WalletTransactionType getByCode(String code) {
        return java.util.Arrays.stream(values())
                .filter(type -> type.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }
}
