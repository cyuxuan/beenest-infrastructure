package club.beenest.payment.object.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 支付事件类型枚举
 *
 * @author System
 * @since 2026-02-11
 */
@Getter
@AllArgsConstructor
public enum PaymentEventType {
    CALLBACK("CALLBACK", "支付回调"),
    NOTIFY("NOTIFY", "异步通知"),
    REFUND_NOTIFY("REFUND_NOTIFY", "退款通知");

    private final String code;
    private final String description;

    public static PaymentEventType getByCode(String code) {
        return Arrays.stream(values())
                .filter(type -> type.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }
}
