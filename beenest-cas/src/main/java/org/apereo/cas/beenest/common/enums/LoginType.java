package org.apereo.cas.beenest.common.enums;

import lombok.Getter;

/**
 * 登录类型枚举
 * <p>
 * 定义系统支持的所有认证方式，每种方式对应一个 AuthenticationHandler 实现。
 */
@Getter
public enum LoginType {

    USERNAME_PASSWORD("USERNAME_PASSWORD", "用户名密码登录", "appTokenAuthenticationHandler"),
    WECHAT("WECHAT", "微信小程序登录", "wechatMiniAuthenticationHandler"),
    DOUYIN_MINI("DOUYIN_MINI", "抖音小程序登录", "douyinMiniAuthenticationHandler"),
    ALIPAY_MINI("ALIPAY_MINI", "支付宝小程序登录", "alipayMiniAuthenticationHandler"),
    PHONE_SMS("PHONE_SMS", "手机短信验证码登录", "smsOtpAuthenticationHandler"),
    APP("APP", "手机APP登录", "appTokenAuthenticationHandler");

    private final String code;
    private final String description;
    private final String handlerName;

    LoginType(String code, String description, String handlerName) {
        this.code = code;
        this.description = description;
        this.handlerName = handlerName;
    }

    /**
     * 根据 code 获取 LoginType
     */
    public static LoginType fromCode(String code) {
        for (LoginType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的登录类型: " + code);
    }

    public static String handlerNameOf(String code) {
        return fromCode(code).getHandlerName();
    }
}
