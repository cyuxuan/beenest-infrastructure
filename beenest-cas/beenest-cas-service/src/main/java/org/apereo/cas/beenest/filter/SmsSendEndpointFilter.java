package org.apereo.cas.beenest.filter;

import org.apereo.cas.beenest.common.exception.BizException;
import org.apereo.cas.beenest.dto.SmsSendResultDTO;
import org.apereo.cas.beenest.service.SmsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 短信验证码直通端点过滤器
 * <p>
 * 为了绕过 CAS 默认安全链对登录入口的拦截，
 * 该过滤器在最前面直接处理 {@code /sms/send} 的 POST 请求。
 * 同时兼容 GET 请求（但不推荐，因手机号会暴露在 URL 和服务器日志中）。
 */
@Slf4j
@RequiredArgsConstructor
public class SmsSendEndpointFilter extends OncePerRequestFilter {

    private final SmsService smsService;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean getWarningLogged = new AtomicBoolean(false);

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain) throws ServletException, IOException {
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        // GET 请求将手机号暴露在 URL 中，仅首次打印提示
        if ("GET".equalsIgnoreCase(method) && getWarningLogged.compareAndSet(false, true)) {
            LOGGER.info("SMS 发送使用了 GET 方法，手机号将暴露在 URL 和日志中，建议迁移到 POST");
        }

        String phone = request.getParameter("phone");
        if (StringUtils.isBlank(phone)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"timestamp\":null,\"status\":400,\"error\":\"Bad Request\",\"message\":\"phone 不能为空\",\"path\":\"/sms/send\"}");
            return;
        }

        try {
            smsService.sendOtp(phone);
            SmsSendResultDTO data = new SmsSendResultDTO();
            data.setPhone(phone);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(data));
        } catch (BizException e) {
            response.setStatus(e.getCode());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"timestamp\":null,\"status\":" + e.getCode() + ",\"error\":\"Forbidden\",\"message\":\"" + e.getMessage() + "\",\"path\":\"/sms/send\"}");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getServletPath();
        return !("/sms/send".equals(requestUri));
    }
}
