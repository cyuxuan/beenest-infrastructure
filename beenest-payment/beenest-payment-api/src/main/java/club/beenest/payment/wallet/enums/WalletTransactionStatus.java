package club.beenest.payment.wallet.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 钱包交易状态枚举
 * 
 * @author System
 * @since 2026-01-28
 */
@Getter
@AllArgsConstructor
public enum WalletTransactionStatus {
    SUCCESS("SUCCESS", "成功"),
    FAILED("FAILED", "失败"),
    PROCESSING("PROCESSING", "处理中");

    private final String code;
    private final String description;
}
