package org.apereo.cas.beenest.filter;

import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.config.CasServiceCredentialProperties;
import org.apereo.cas.beenest.service.CasServiceCredentialService;
import org.apereo.cas.beenest.util.CasRequestSignatureUtils;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 业务系统登录凭证过滤器。
 * <p>
 * 只拦截 APP / 小程序登录入口，校验业务系统签名、时间戳和 nonce，
 * 通过后再进入 CAS 登录控制器。
 */
@Slf4j
public class CasServiceCredentialFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/app/login",
            "/app/refresh",
            "/miniapp/wechat/login",
            "/miniapp/douyin/login",
            "/miniapp/alipay/login",
            "/miniapp/refresh",
            "/miniapp/sms/send"
    );

    private final CasServiceCredentialService credentialService;
    private final CasServiceCredentialProperties properties;
    private final StringRedisTemplate redisTemplate;

    /**
     * 构造业务系统凭证过滤器。
     *
     * @param credentialService 凭证服务
     * @param properties        凭证相关配置
     * @param redisTemplate     Redis 访问器
     */
    public CasServiceCredentialFilter(CasServiceCredentialService credentialService,
                                      CasServiceCredentialProperties properties,
                                      StringRedisTemplate redisTemplate) {
        this.credentialService = credentialService;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !PROTECTED_PATHS.contains(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);
        String body = wrappedRequest.getCachedBody();

        String serviceIdText = readHeader(request, CasConstant.SERVICE_ID_HEADER, CasConstant.BUSINESS_SERVICE_ID_HEADER);
        String timestamp = readHeader(request, CasConstant.REQUEST_TIMESTAMP_HEADER, CasConstant.BUSINESS_TIMESTAMP_HEADER);
        String nonce = readHeader(request, CasConstant.REQUEST_NONCE_HEADER, CasConstant.BUSINESS_NONCE_HEADER);
        String signature = readHeader(request, CasConstant.REQUEST_SIGNATURE_HEADER, CasConstant.BUSINESS_SIGNATURE_HEADER);

        if (StringUtils.isAnyBlank(serviceIdText, timestamp, nonce, signature)) {
            writeError(response, 400, "缺少业务系统签名请求头");
            return;
        }

        Long serviceId = parseServiceId(serviceIdText, response);
        if (serviceId == null) {
            return;
        }

        if (!isTimestampValid(timestamp)) {
            writeError(response, 403, "timestamp 已过期或非法");
            return;
        }

        String plainSecret;
        try {
            plainSecret = credentialService.resolvePlainSecret(serviceId);
        } catch (Exception e) {
            LOGGER.warn("解密业务系统 secret 失败: serviceId={}", serviceId, e);
            writeError(response, 500, "系统凭证解密失败");
            return;
        }
        if (StringUtils.isBlank(plainSecret)) {
            writeError(response, 403, "业务系统密钥无效");
            return;
        }

        String expected = CasRequestSignatureUtils.sign(timestamp, nonce, body, plainSecret);
        if (!CasRequestSignatureUtils.constantTimeEquals(expected, signature)) {
            writeError(response, 403, "signature 签名验证失败");
            return;
        }

        if (!markNonce(serviceId, nonce)) {
            writeError(response, 403, "nonce 已被使用，疑似重放");
            return;
        }
        filterChain.doFilter(wrappedRequest, response);
    }

    private Long parseServiceId(String serviceIdText, HttpServletResponse response) throws IOException {
        if (StringUtils.isBlank(serviceIdText)) {
            writeError(response, 403, "缺少业务系统标识");
            return null;
        }
        try {
            return Long.parseLong(serviceIdText.trim());
        } catch (NumberFormatException e) {
            writeError(response, 400, "业务系统标识格式错误");
            return null;
        }
    }

    private boolean isTimestampValid(String timestampText) {
        try {
            long value = Long.parseLong(timestampText.trim());
            long now = Instant.now().getEpochSecond();
            long requestTime = value > 10_000_000_000L ? TimeUnit.MILLISECONDS.toSeconds(value) : value;
            long skew = properties.getAllowedClockSkewSeconds();
            return Math.abs(now - requestTime) <= skew;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean markNonce(Long serviceId, String nonce) {
        String key = CasConstant.REDIS_SERVICE_NONCE_PREFIX + serviceId + ":" + nonce;
        Boolean set = redisTemplate.opsForValue().setIfAbsent(
                key, String.valueOf(Instant.now().getEpochSecond()), properties.getNonceTtlSeconds(), TimeUnit.SECONDS);
        return Boolean.TRUE.equals(set);
    }

    private String readHeader(final HttpServletRequest request,
                              final String primaryName,
                              final String fallbackName) {
        String value = request.getHeader(primaryName);
        return StringUtils.isNotBlank(value) ? value : request.getHeader(fallbackName);
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format("{\"code\":%d,\"message\":\"%s\",\"data\":null}", status, message));
    }

    /**
     * 可重复读取 body 的请求包装器。
     */
    private static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        private String getCachedBody() {
            return new String(body, StandardCharsets.UTF_8);
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return inputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }

                @Override
                public int read() {
                    return inputStream.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
