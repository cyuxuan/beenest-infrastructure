package club.beenest.payment.paymentorder.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 支付事件状态枚举
 *
 * @author System
 * @since 2026-02-11
 */
@Getter
@AllArgsConstructor
public enum PaymentEventStatus {
    PENDING("PENDING", "待处理"),
    SUCCESS("SUCCESS", "成功"),
    FAILED("FAILED", "失败"),
    RETRY("RETRY", "重试");

    private final String code;
    private final String description;

    public static PaymentEventStatus getByCode(String code) {
        return Arrays.stream(values())
                .filter(status -> status.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }
}
