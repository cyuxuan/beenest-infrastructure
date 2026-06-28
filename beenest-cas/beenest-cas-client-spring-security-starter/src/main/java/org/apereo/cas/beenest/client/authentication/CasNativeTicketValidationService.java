package org.apereo.cas.beenest.client.authentication;

import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import org.apereo.cas.beenest.client.session.CasUserSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAS 原生票据验证服务。
 * <p>
 * 按 CAS 官方协议直接完成 TGT -> ST -> /p3/serviceValidate 的完整链路，
 * 这样客户端不再依赖服务端额外暴露的兼容验证接口。
 */
@Slf4j
public class CasNativeTicketValidationService {

    private final CasSecurityProperties properties;
    private final RestTemplate restTemplate;

    /**
     * 构造原生票据验证服务。
     *
     * @param properties CAS 客户端配置
     * @param restTemplate HTTP 客户端
     */
    public CasNativeTicketValidationService(CasSecurityProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    /**
     * 验证 TGT 并返回用户会话。
     *
     * @param accessToken TGT
     * @return 用户会话，验证失败返回 null
     */
    public CasUserSession validate(String accessToken) {
        String serviceUrl = properties.resolveValidationServiceUrl();
        log.debug("[TGT验证] 开始: accessToken={}..., serviceUrl={}, serverUrl={}",
            accessToken != null ? accessToken.substring(0, Math.min(20, accessToken.length())) : "null",
            serviceUrl, properties.getServerUrl());
        if (!StringUtils.hasText(accessToken) || !StringUtils.hasText(serviceUrl) || !StringUtils.hasText(properties.getServerUrl())) {
            log.warn("[TGT验证] 前置参数缺失: hasAccessToken={}, hasServiceUrl={}, hasServerUrl={}",
                StringUtils.hasText(accessToken), StringUtils.hasText(serviceUrl), StringUtils.hasText(properties.getServerUrl()));
            return null;
        }

        try {
            String serviceTicket = requestServiceTicket(accessToken, serviceUrl);
            if (!StringUtils.hasText(serviceTicket)) {
                log.warn("[TGT验证] ST 兑换失败: accessToken={}..., serviceUrl={}",
                    accessToken.substring(0, Math.min(20, accessToken.length())), serviceUrl);
                return null;
            }
            log.debug("[TGT验证] ST 兑换成功: ST={}...", serviceTicket.substring(0, Math.min(12, serviceTicket.length())));
            CasUserSession session = validateServiceTicket(serviceUrl, serviceTicket);
            if (session != null) {
                log.debug("[TGT验证] ST 验证成功: userId={}", session.getUserId());
            } else {
                log.warn("[TGT验证] ST 验证返回 null: serviceUrl={}, ST={}...", serviceUrl, serviceTicket.substring(0, Math.min(12, serviceTicket.length())));
            }
            return session;
        } catch (Exception e) {
            log.error("[TGT验证] 异常: error={}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 通过 CAS 原生 REST 端点兑换 ST。
     *
     * @param accessToken TGT
     * @param serviceUrl 目标服务地址
     * @return ST，失败返回 null
     */
    @SuppressWarnings("null")
    private String requestServiceTicket(String accessToken, String serviceUrl) {
        String ticketUrl = UriComponentsBuilder.fromUriString(normalizeServerPrefix(properties.getServerUrl()))
            .path("/v1/tickets/")
            .path(UriUtils.encodePathSegment(accessToken, StandardCharsets.UTF_8))
            .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("service", serviceUrl);

        log.debug("[ST兑换] 请求: url={}, service={}", ticketUrl, serviceUrl);
        ResponseEntity<String> response = restTemplate.postForEntity(ticketUrl, new HttpEntity<>(body, headers), String.class);
        log.debug("[ST兑换] 响应: status={}, Location={}, body={}",
            response.getStatusCode(), response.getHeaders().getFirst(HttpHeaders.LOCATION),
            response.getBody() != null ? response.getBody().substring(0, Math.min(100, response.getBody().length())) : "null");

        String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        if (StringUtils.hasText(location)) {
            return extractTicketFromLocation(location);
        }
        return extractTicketFromBody(response.getBody());
    }

    /**
     * 使用 CAS /p3/serviceValidate 校验 ST。
     *
     * @param serviceUrl 服务地址
     * @param serviceTicket 服务票据
     * @return 用户会话，失败返回 null
     * @throws Exception XML 解析异常
     */
    @SuppressWarnings("null")
    private CasUserSession validateServiceTicket(String serviceUrl, String serviceTicket) throws Exception {
        String validateUrl = UriComponentsBuilder.fromUriString(normalizeServerPrefix(properties.getServerUrl()))
            .path("/p3/serviceValidate")
            .queryParam("service", serviceUrl)
            .queryParam("ticket", serviceTicket)
            .toUriString();

        log.debug("[ST验证] 请求: url={}", validateUrl);
        ResponseEntity<String> response = restTemplate.getForEntity(validateUrl, String.class);
        String responseBody = response.getBody();
        log.debug("[ST验证] 响应: status={}, bodyLength={}, body={}",
            response.getStatusCode(),
            responseBody != null ? responseBody.length() : 0,
            responseBody != null ? responseBody.substring(0, Math.min(500, responseBody.length())) : "null");
        return parseServiceValidateResponse(responseBody);
    }

    /**
     * 解析 CAS /p3/serviceValidate XML。
     *
     * @param responseBody XML 响应体
     * @return 用户会话，失败返回 null
     * @throws Exception XML 解析异常
     */
    private CasUserSession parseServiceValidateResponse(String responseBody) throws Exception {
        if (!StringUtils.hasText(responseBody)) {
            log.warn("[ST验证] 响应体为空");
            return null;
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder()
            .parse(new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8)));
        document.getDocumentElement().normalize();

        XPath xPath = XPathFactory.newInstance().newXPath();
        Boolean success = (Boolean) xPath.evaluate("boolean(//*[local-name()='authenticationSuccess'])", document, XPathConstants.BOOLEAN);
        if (success == null || !success) {
            // 提取错误码用于诊断
            String errorCode = xPath.evaluate("//*[local-name()='authenticationFailure']/@code", document);
            String errorText = xPath.evaluate("//*[local-name()='authenticationFailure']/text()", document);
            log.warn("[ST验证] 验证失败: errorCode={}, errorText={}", errorCode, errorText != null ? errorText.trim() : "null");
            return null;
        }

        String userId = xPath.evaluate("//*[local-name()='authenticationSuccess']/*[local-name()='user']/text()", document);
        if (!StringUtils.hasText(userId)) {
            log.warn("[ST验证] 认证成功但 userId 为空");
            return null;
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        NodeList attributeNodes = (NodeList) xPath.evaluate("//*[local-name()='authenticationSuccess']/*[local-name()='attributes']/*", document, XPathConstants.NODESET);
        for (int i = 0; i < attributeNodes.getLength(); i++) {
            Node node = attributeNodes.item(i);
            String key = node.getLocalName();
            String value = node.getTextContent();
            if (StringUtils.hasText(key) && value != null) {
                // 多值属性（如 memberOf 有多个 XML 元素）合并为 List<String>，
                // 单值属性保留为 String
                Object existing = attributes.get(key);
                if (existing == null) {
                    // 首次出现 → 单值
                    attributes.put(key, value);
                } else if (existing instanceof String strVal) {
                    // 第二次出现 → 升级为 List
                    List<String> list = new ArrayList<>();
                    list.add(strVal);
                    list.add(value);
                    attributes.put(key, list);
                } else if (existing instanceof List<?> list) {
                    // 已是 List → 追加
                    ((List<String>) list).add(value);
                }
            }
        }

        CasUserSession session = new CasUserSession();
        session.setUserId(userId);
        session.setUsername(getStrAttr(attributes, "username"));
        session.setNickname(getStrAttr(attributes, "nickname"));
        session.setUserType(getStrAttr(attributes, "userType"));
        session.setPhone(getStrAttr(attributes, "phone"));
        session.setEmail(getStrAttr(attributes, "email"));
        session.setAvatarUrl(getStrAttr(attributes, "avatarUrl"));
        session.setIdentity(getStrAttr(attributes, "identity"));
        session.setAttributes(attributes);
        return session;
    }

    /**
     * 解析 ST 颁发响应中的 Location。
     *
     * @param location Location 头
     * @return ST
     */
    private String extractTicketFromLocation(String location) {
        try {
            URI uri = URI.create(location);
            String path = uri.getPath();
            if (!StringUtils.hasText(path)) {
                return null;
            }
            int index = path.lastIndexOf('/');
            return index >= 0 ? path.substring(index + 1) : path;
        } catch (Exception e) {
            log.debug("解析 ST Location 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 兼容某些实现直接在响应体中返回 ST 的情况。
     *
     * @param body 响应体
     * @return ST
     */
    private String extractTicketFromBody(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("ST-")) {
            return trimmed;
        }
        if (trimmed.contains("ST-")) {
            int index = trimmed.indexOf("ST-");
            int end = trimmed.indexOf('\n', index);
            return end > index ? trimmed.substring(index, end).trim() : trimmed.substring(index).trim();
        }
        return null;
    }

    /**
     * 规范化 CAS Server 前缀。
     *
     * @param prefix CAS 前缀
     * @return 标准化前缀
     */
    private String normalizeServerPrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return "";
        }
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    /**
     * 从属性 Map 中提取字符串值（兼容单值 String 和多值 List）。
     *
     * @param attrs 属性 Map
     * @param key   属性键
     * @return 字符串值，多值时取第一个元素
     */
    private String getStrAttr(Map<String, Object> attrs, String key) {
        Object value = attrs.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            return first != null ? first.toString() : null;
        }
        return value.toString();
    }

}
