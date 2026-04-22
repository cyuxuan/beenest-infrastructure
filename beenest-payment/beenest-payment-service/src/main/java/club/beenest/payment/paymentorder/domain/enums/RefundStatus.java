package club.beenest.payment.paymentorder.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 退款状态枚举
 *
 * @author System
 * @since 2026-02-11
 */
@Getter
@AllArgsConstructor
public enum RefundStatus {
    /** 待处理/待审核 */
    PENDING("PENDING", "待处理"),
    /** 处理中（已提交给第三方） */
    PROCESSING("PROCESSING", "处理中"),
    /** 退款成功 */
    SUCCESS("SUCCESS", "退款成功"),
    /** 退款失败 */
    FAILED("FAILED", "退款失败"),
    /** 审核拒绝 */
    REJECTED("REJECTED", "审核拒绝");

    private final String code;
    private final String description;

    /**
     * 检查是否可以流转到目标状态
     *
     * @param nextStatus 目标状态
     * @return true表示可以流转
     */
    public boolean canTransitionTo(RefundStatus nextStatus) {
        if (nextStatus == null) {
            return false;
        }
        
        if (this == nextStatus) {
            return true;
        }

        return switch (this) {
            case PENDING -> nextStatus == PROCESSING || nextStatus == REJECTED || nextStatus == SUCCESS || nextStatus == FAILED;
            case PROCESSING -> nextStatus == SUCCESS || nextStatus == FAILED;
            case FAILED -> nextStatus == PROCESSING; // 允许重试
            // 终态不可流转
            case SUCCESS, REJECTED -> false;
        };
    }

    public static RefundStatus getByCode(String code) {
        return Arrays.stream(values())
                .filter(status -> status.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }
}
