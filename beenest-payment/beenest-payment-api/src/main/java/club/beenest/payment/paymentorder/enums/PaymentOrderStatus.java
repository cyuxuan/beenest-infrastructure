package club.beenest.payment.paymentorder.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 支付订单状态枚举
 *
 * @author System
 * @since 2026-02-11
 */
@Getter
@AllArgsConstructor
public enum PaymentOrderStatus {
    /** 待支付 */
    PENDING("PENDING", "待支付"),
    /** 已支付 */
    PAID("PAID", "已支付"),
    /** 已取消 */
    CANCELLED("CANCELLED", "已取消"),
    /** 已过期 */
    EXPIRED("EXPIRED", "已过期"),
    /** 已退款（全额） */
    REFUNDED("REFUNDED", "已退款"),
    /** 部分退款 */
    PARTIAL_REFUNDED("PARTIAL_REFUNDED", "部分退款");

    private final String code;
    private final String description;

    /**
     * 检查是否可以流转到目标状态
     *
     * @param nextStatus 目标状态
     * @return true表示可以流转，false表示不可流转
     */
    public boolean canTransitionTo(PaymentOrderStatus nextStatus) {
        if (nextStatus == null) {
            return false;
        }
        
        // 状态未改变，视为允许（或根据业务需求视为无效）
        if (this == nextStatus) {
            return true;
        }

        return switch (this) {
            case PENDING -> nextStatus == PAID || nextStatus == CANCELLED || nextStatus == EXPIRED;
            case PAID -> nextStatus == REFUNDED || nextStatus == PARTIAL_REFUNDED;
            case PARTIAL_REFUNDED -> nextStatus == REFUNDED;
            // 终态不可流转
            case CANCELLED, EXPIRED, REFUNDED -> false;
        };
    }

    public static PaymentOrderStatus getByCode(String code) {
        return Arrays.stream(values())
                .filter(status -> status.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }
}
