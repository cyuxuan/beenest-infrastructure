package org.apereo.cas.beenest.filter;

import org.apereo.cas.beenest.common.exception.BusinessException;
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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 短信验证码直通端点过滤器
 * <p>
 * 为了绕过 CAS 默认安全链对登录入口的拦截，
 * 该过滤器在最前面直接处理 `/cas/sms/send?phone=...` 的 GET 请求。
 */
@Slf4j
@RequiredArgsConstructor
public class SmsSendEndpointFilter extends OncePerRequestFilter {

    private final SmsService smsService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        LOGGER.info("SMS send filter invoked: method={}, uri={}", request.getMethod(), request.getRequestURI());
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
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
        } catch (BusinessException e) {
            response.setStatus(e.getCode());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"timestamp\":null,\"status\":" + e.getCode() + ",\"error\":\"Forbidden\",\"message\":\"" + e.getMessage() + "\",\"path\":\"/sms/send\"}");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getServletPath();
        return requestUri == null
                || !("/sms/send".equals(requestUri) || "/sms/send".equals(requestUri));
    }
}
