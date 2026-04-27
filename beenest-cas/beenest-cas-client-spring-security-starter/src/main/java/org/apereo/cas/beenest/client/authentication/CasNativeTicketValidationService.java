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
import java.util.LinkedHashMap;
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
        if (!StringUtils.hasText(accessToken) || !StringUtils.hasText(serviceUrl) || !StringUtils.hasText(properties.getServerUrl())) {
            return null;
        }

        try {
            String serviceTicket = requestServiceTicket(accessToken, serviceUrl);
            if (!StringUtils.hasText(serviceTicket)) {
                return null;
            }
            return validateServiceTicket(serviceUrl, serviceTicket);
        } catch (Exception e) {
            log.error("CAS 原生票据验证失败: error={}", e.getMessage());
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
    private String requestServiceTicket(String accessToken, String serviceUrl) {
        String ticketUrl = UriComponentsBuilder.fromUriString(normalizeServerPrefix(properties.getServerUrl()))
            .path("/v1/tickets/")
            .path(UriUtils.encodePathSegment(accessToken, StandardCharsets.UTF_8))
            .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("service", serviceUrl);

        ResponseEntity<String> response = restTemplate.postForEntity(ticketUrl, new HttpEntity<>(body, headers), String.class);
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
    private CasUserSession validateServiceTicket(String serviceUrl, String serviceTicket) throws Exception {
        String validateUrl = UriComponentsBuilder.fromUriString(normalizeServerPrefix(properties.getServerUrl()))
            .path("/p3/serviceValidate")
            .queryParam("service", serviceUrl)
            .queryParam("ticket", serviceTicket)
            .toUriString();

        ResponseEntity<String> response = restTemplate.getForEntity(validateUrl, String.class);
        return parseServiceValidateResponse(response.getBody());
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
            return null;
        }

        String userId = xPath.evaluate("//*[local-name()='authenticationSuccess']/*[local-name()='user']/text()", document);
        if (!StringUtils.hasText(userId)) {
            return null;
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        NodeList attributeNodes = (NodeList) xPath.evaluate("//*[local-name()='authenticationSuccess']/*[local-name()='attributes']/*", document, XPathConstants.NODESET);
        for (int i = 0; i < attributeNodes.getLength(); i++) {
            Node node = attributeNodes.item(i);
            String key = node.getLocalName();
            String value = node.getTextContent();
            if (StringUtils.hasText(key) && value != null) {
                attributes.putIfAbsent(key, value);
            }
        }

        CasUserSession session = new CasUserSession();
        session.setUserId(userId);
        session.setUsername(attributes.get("username"));
        session.setNickname(attributes.get("nickname"));
        session.setUserType(attributes.get("userType"));
        session.setPhone(attributes.get("phone"));
        session.setEmail(attributes.get("email"));
        session.setAvatarUrl(attributes.get("avatarUrl"));
        session.setIdentity(attributes.get("identity"));
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

}
