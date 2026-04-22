package club.beenest.payment.paymentorder.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OrderRefundPolicy {
    NOT_PAID("NOT_PAID", "未支付，不涉及退款"),
    AUTO_REFUND("AUTO_REFUND", "服务未开始，可直接退单并原路退款"),
    MANUAL_REVIEW("MANUAL_REVIEW", "服务已进入履约阶段，需人工审核退款"),
    NOT_ALLOWED("NOT_ALLOWED", "当前状态不允许退款");

    private final String code;
    private final String description;
}
