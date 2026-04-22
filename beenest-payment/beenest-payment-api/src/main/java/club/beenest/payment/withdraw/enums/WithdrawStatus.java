package club.beenest.payment.withdraw.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 提现状态枚举
 *
 * @author System
 * @since 2026-02-11
 */
@Getter
@AllArgsConstructor
public enum WithdrawStatus {
    /** 待审核 */
    PENDING("PENDING", "待审核"),
    /** 人工审核 */
    MANUAL_REVIEW("MANUAL_REVIEW", "人工审核"),
    /** 已审核/待打款 */
    APPROVED("APPROVED", "已审核"),
    /** 处理中（打款中） */
    PROCESSING("PROCESSING", "处理中"),
    /** 提现成功 */
    SUCCESS("SUCCESS", "提现成功"),
    /** 提现失败 */
    FAILED("FAILED", "提现失败"),
    /** 已拒绝 */
    REJECTED("REJECTED", "已拒绝"),
    /** 已取消 */
    CANCELLED("CANCELLED", "已取消"),
    /** 处理异常-待人工介入（第三方可能已扣款，不能自动释放资金） */
    PROCESSING_ERROR("PROCESSING_ERROR", "处理异常-待人工介入");

    private final String code;
    private final String description;

    /**
     * 检查是否可以流转到目标状态
     */
    public boolean canTransitionTo(WithdrawStatus nextStatus) {
        if (nextStatus == null) {
            return false;
        }

        if (this == nextStatus) {
            return true;
        }

        return switch (this) {
            case PENDING -> nextStatus == APPROVED || nextStatus == CANCELLED || nextStatus == REJECTED || nextStatus == MANUAL_REVIEW;
            case MANUAL_REVIEW -> nextStatus == APPROVED || nextStatus == CANCELLED || nextStatus == REJECTED;
            case APPROVED -> nextStatus == PROCESSING || nextStatus == SUCCESS || nextStatus == FAILED || nextStatus == CANCELLED;
            case PROCESSING -> nextStatus == SUCCESS || nextStatus == FAILED || nextStatus == PROCESSING_ERROR;
            case PROCESSING_ERROR -> nextStatus == SUCCESS || nextStatus == FAILED;
            case FAILED -> nextStatus == PENDING; // 允许重试
            // 终态
            case SUCCESS, CANCELLED, REJECTED -> false;
        };
    }

    /**
     * 是否为终态（不可再流转）
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == CANCELLED || this == REJECTED;
    }

    public static WithdrawStatus getByCode(String code) {
        return Arrays.stream(values())
                .filter(status -> status.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }
}
