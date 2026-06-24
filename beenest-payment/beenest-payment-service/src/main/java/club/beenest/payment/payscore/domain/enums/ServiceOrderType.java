package club.beenest.payment.payscore.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 服务订单类型枚举
 * 标识支付分的平台来源
 *
 * @author System
 * @since 2026-06-15
 */
@Getter
@AllArgsConstructor
public enum ServiceOrderType {

    /** 微信支付分 */
    WECHAT_PAYSCORE("WECHAT_PAYSCORE", "微信支付分"),

    /** 支付宝芝麻信用 */
    ALIPAY_ZHIMA("ALIPAY_ZHIMA", "支付宝芝麻信用");

    private final String code;
    private final String description;

    /**
     * 根据编码获取枚举
     *
     * @param code 编码
     * @return 枚举值，未找到返回null
     */
    public static ServiceOrderType getByCode(String code) {
        return Arrays.stream(values())
                .filter(type -> type.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }
}
