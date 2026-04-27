package org.apereo.cas.beenest.authn.web;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 浏览器登录模式解析器。
 * <p>
 * CAS 登录页会通过隐藏字段 {@code loginMethod} 区分用户名密码与短信验证码模式。
 * Handler 在认证阶段通过当前请求读取该参数，避免靠输入形状猜测登录方式。
 */
public final class WebLoginModeResolver {

    public static final String LOGIN_METHOD_SMS = "sms";
    public static final String LOGIN_METHOD_PASSWORD = "password";

    private WebLoginModeResolver() {
    }

    public static String resolveLoginMethod() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            return LOGIN_METHOD_PASSWORD;
        }
        HttpServletRequest request = servletRequestAttributes.getRequest();
        return StringUtils.defaultIfBlank(request.getParameter("loginMethod"), LOGIN_METHOD_PASSWORD);
    }

    public static boolean isSmsMode() {
        return LOGIN_METHOD_SMS.equalsIgnoreCase(resolveLoginMethod());
    }

    public static boolean isPasswordMode() {
        return !isSmsMode();
    }
}
