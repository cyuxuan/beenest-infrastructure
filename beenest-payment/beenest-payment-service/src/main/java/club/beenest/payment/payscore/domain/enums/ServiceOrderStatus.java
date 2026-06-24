package club.beenest.payment.payscore.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 服务订单状态枚举
 * 定义支付分信用免押服务订单的完整生命周期状态
 *
 * <p>状态流转：</p>
 * <pre>
 *   PENDING_AUTH → AUTHORIZED（用户授权回调确认）
 *   PENDING_AUTH → EXPIRED（授权超时）
 *   PENDING_AUTH → CANCELLED（用户取消授权）
 *   AUTHORIZED → SERVICE_ACTIVE（商户开始入驻）
 *   SERVICE_ACTIVE → COMPLETING（发起完结扣款）
 *   COMPLETING → COMPLETED（完结成功）
 *   COMPLETING → FAILED（完结失败）
 *   AUTHORIZED → CANCELLED（取消授权解冻）
 * </pre>
 *
 * @author System
 * @since 2026-06-15
 */
@Getter
@AllArgsConstructor
public enum ServiceOrderStatus {

    /** 待授权 - 等待用户跳转授权页面确认 */
    PENDING_AUTH("PENDING_AUTH", "待授权"),

    /** 已授权 - 用户已确认，信用额度已冻结 */
    AUTHORIZED("AUTHORIZED", "已授权"),

    /** 服务中 - 商户已入驻，服务进行中 */
    SERVICE_ACTIVE("SERVICE_ACTIVE", "服务中"),

    /** 完结中 - 已发起完结扣款请求，等待确认 */
    COMPLETING("COMPLETING", "完结中"),

    /** 已完结 - 扣款完成，剩余额度已解冻 */
    COMPLETED("COMPLETED", "已完结"),

    /** 已取消 - 授权已取消，额度已解冻 */
    CANCELLED("CANCELLED", "已取消"),

    /** 已过期 - 授权超时未确认 */
    EXPIRED("EXPIRED", "已过期"),

    /** 失败 - 授权失败或完结失败 */
    FAILED("FAILED", "失败");

    private final String code;
    private final String description;

    /**
     * 检查是否可以流转到目标状态
     *
     * @param nextStatus 目标状态
     * @return true表示可以流转，false表示不可流转
     */
    public boolean canTransitionTo(ServiceOrderStatus nextStatus) {
        if (nextStatus == null) {
            return false;
        }
        // 状态未改变，视为允许
        if (this == nextStatus) {
            return true;
        }
        return switch (this) {
            case PENDING_AUTH -> nextStatus == AUTHORIZED
                    || nextStatus == EXPIRED
                    || nextStatus == CANCELLED
                    || nextStatus == FAILED;
            case AUTHORIZED -> nextStatus == SERVICE_ACTIVE
                    || nextStatus == CANCELLED;
            case SERVICE_ACTIVE -> nextStatus == COMPLETING
                    || nextStatus == CANCELLED;
            case COMPLETING -> nextStatus == COMPLETED
                    || nextStatus == FAILED;
            // 终态不可流转
            case COMPLETED, CANCELLED, EXPIRED, FAILED -> false;
        };
    }

    /**
     * 根据状态码获取枚举
     *
     * @param code 状态码
     * @return 枚举值，未找到返回null
     */
    public static ServiceOrderStatus getByCode(String code) {
        return Arrays.stream(values())
                .filter(status -> status.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断是否为终态（不可再变更）
     *
     * @return true表示终态
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == EXPIRED || this == FAILED;
    }
}
