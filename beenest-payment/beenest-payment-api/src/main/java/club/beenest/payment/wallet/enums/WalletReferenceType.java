package club.beenest.payment.wallet.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 钱包交易关联类型枚举
 * 
 * @author System
 * @since 2026-01-28
 */
@Getter
@AllArgsConstructor
public enum WalletReferenceType {
    ORDER("ORDER", "订单"),
    RED_PACKET("RED_PACKET", "红包"),
    COUPON("COUPON", "优惠券"),
    WITHDRAW_REQUEST("WITHDRAW_REQUEST", "提现申请"),
    PAYMENT_ORDER("PAYMENT_ORDER", "充值订单");

    private final String code;
    private final String description;
}
