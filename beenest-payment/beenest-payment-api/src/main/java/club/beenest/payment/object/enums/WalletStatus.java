package club.beenest.payment.object.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 钱包状态枚举
 * 
 * @author System
 * @since 2026-01-28
 */
@Getter
@AllArgsConstructor
public enum WalletStatus {
    ACTIVE("ACTIVE", "正常"),
    FROZEN("FROZEN", "冻结"),
    CLOSED("CLOSED", "已关闭");

    private final String code;
    private final String description;
}
