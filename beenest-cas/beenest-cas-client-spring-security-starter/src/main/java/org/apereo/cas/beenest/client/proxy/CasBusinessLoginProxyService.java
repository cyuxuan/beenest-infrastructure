package org.apereo.cas.beenest.client.proxy;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import org.apereo.cas.beenest.client.util.RequestSignatureUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Enumeration;

/**
 * 业务系统登录代理服务。
 * <p>
 * 业务系统通过 starter 暴露登录地址，代理服务将请求原样转发到 CAS Server，
 * 并把 CAS Server 的响应直接返回给调用方。
 */
@Slf4j
public class CasBusinessLoginProxyService {

    private final CasSecurityProperties properties;
    private final RestTemplate restTemplate;

    /**
     * 构造业务系统登录代理服务。
     *
     * @param properties CAS Starter 配置
     * @param restTemplate 用于访问 CAS Server 的 HTTP 客户端
     */
    public CasBusinessLoginProxyService(CasSecurityProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    /**
     * 代理业务系统登录请求到 CAS Server。
     *
     * @param request 当前 HTTP 请求
     * @param body 请求体
     * @return CAS Server 的原始响应
     */
    public ResponseEntity<String> proxy(HttpServletRequest request, String body, String targetPath) {
        String targetUrl = buildTargetUrl(targetPath, request.getQueryString());
        try {
            HttpHeaders headers = copyHeaders(request);
            appendSignatureHeaders(headers, body);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(targetUrl, HttpMethod.POST, entity, String.class);
            LOGGER.info("业务系统登录请求已转发到 CAS: path={}, status={}", request.getRequestURI(), response.getStatusCode());
            return ResponseEntity.status(response.getStatusCode())
                .headers(filterResponseHeaders(response.getHeaders()))
                .body(response.getBody());
        } catch (Exception e) {
            LOGGER.error("转发 CAS 登录请求失败: path={}, targetUrl={}", request.getRequestURI(), targetUrl, e);
            return ResponseEntity.status(502)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":502,\"message\":\"CAS 登录服务暂不可用\"}");
        }
    }

    /**
     * 构建 CAS Server 目标地址。
     * <p>
     * 当 serverUrl 已经包含 /cas 前缀时，避免与请求路径中的 /cas 重复拼接。
     *
     * @param request 当前请求
     * @return CAS Server 目标 URL
     */
    private String buildTargetUrl(String targetPath, String queryString) {
        String baseUrl = removeTrailingSlash(properties.getServerUrl());
        String requestPath = targetPath;
        if (baseUrl.endsWith("/cas") && requestPath.startsWith("/cas/")) {
            requestPath = requestPath.substring(4);
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl).path(requestPath);
        if (StringUtils.hasText(queryString)) {
            builder.query(queryString);
        }
        return builder.build(true).toUriString();
    }

    /**
     * 复制请求头到 CAS Server。
     * <p>
     * 保留业务系统签名头、时间戳、nonce 等关键字段，
     * 跳过 Host / Content-Length 等 hop-by-hop 头。
     *
     * @param request 当前请求
     * @return 转发请求头
     */
    private HttpHeaders copyHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (shouldSkipHeader(headerName)) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(headerName);
            while (values.hasMoreElements()) {
                headers.add(headerName, values.nextElement());
            }
        }
        if (!headers.containsKey(HttpHeaders.CONTENT_TYPE) && StringUtils.hasText(request.getContentType())) {
            headers.setContentType(MediaType.parseMediaType(request.getContentType()));
        }
        return headers;
    }

    /**
     * 自动附加业务系统签名头。
     * <p>
     * 使用 serviceId + signKey 生成 HMAC-SHA256 签名，
     * 满足 CAS Server 的 CasServiceCredentialFilter 校验要求。
     *
     * @param headers 转发请求头
     * @param body    请求体
     */
    private void appendSignatureHeaders(HttpHeaders headers, String body) {
        String serviceId = properties.getServiceId();
        String signKey = properties.getSignKey();
        if (!StringUtils.hasText(serviceId) || !StringUtils.hasText(signKey)) {
            return;
        }
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = RequestSignatureUtils.generateNonce();
        String signature = RequestSignatureUtils.sign(timestamp, nonce, body != null ? body : "", signKey);
        headers.set("X-CAS-Service-Id", serviceId);
        headers.set("X-CAS-Timestamp", timestamp);
        headers.set("X-CAS-Nonce", nonce);
        headers.set("X-CAS-Signature", signature);
    }

    /**
     * 去除尾部斜杠。
     *
     * @param value 原始字符串
     * @return 去除尾部斜杠后的字符串
     */
    private String removeTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 判断是否跳过某些转发头。
     *
     * @param headerName 头名称
     * @return true 表示跳过
     */
    private boolean shouldSkipHeader(String headerName) {
        if (headerName == null) {
            return true;
        }
        return HttpHeaders.HOST.equalsIgnoreCase(headerName)
            || HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(headerName)
            || HttpHeaders.CONNECTION.equalsIgnoreCase(headerName)
            || HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(headerName);
    }

    /**
     * 过滤 CAS 响应头，避免把 hop-by-hop 头原样透传给业务系统客户端。
     *
     * @param responseHeaders CAS 返回的响应头
     * @return 过滤后的响应头
     */
    private HttpHeaders filterResponseHeaders(HttpHeaders responseHeaders) {
        HttpHeaders filtered = new HttpHeaders();
        if (responseHeaders == null || responseHeaders.isEmpty()) {
            return filtered;
        }

        for (var entry : responseHeaders.entrySet()) {
            String headerName = entry.getKey();
            if (shouldSkipResponseHeader(headerName)) {
                continue;
            }
            for (String value : entry.getValue()) {
                filtered.add(headerName, value);
            }
        }
        return filtered;
    }

    /**
     * 判断是否跳过 CAS 响应头。
     *
     * @param headerName 头名称
     * @return true 表示跳过
     */
    private boolean shouldSkipResponseHeader(String headerName) {
        if (headerName == null) {
            return true;
        }
        return HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(headerName)
            || HttpHeaders.CONNECTION.equalsIgnoreCase(headerName)
            || HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(headerName)
            || HttpHeaders.TRAILER.equalsIgnoreCase(headerName)
            || HttpHeaders.UPGRADE.equalsIgnoreCase(headerName);
    }
}
