package org.apereo.cas.beenest.client.authentication;

import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import org.apereo.cas.beenest.client.session.CasUserSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * TGT 远程验证器
 * <p>
 * 调用 CAS Server 的 /cas/token/validate 端点验证 accessToken (TGT) 有效性。
 * 用于 APP/小程序的 Bearer Token 认证场景。
 */
@Slf4j
public class CasTgtValidator {

    private final CasSecurityProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CasTgtValidator(CasSecurityProperties properties) {
        this(properties, new RestTemplate());
    }

    CasTgtValidator(CasSecurityProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    /**
     * 验证 accessToken (TGT) 有效性
     *
     * @param accessToken TGT ID（Bearer token 的值）
     * @return 用户会话信息，验证失败返回 null
     */
    public CasUserSession validate(String accessToken) {
        String validateUrl = properties.getServerUrl() + "/token/validate";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            if (properties.getTokenValidationSecret() != null && !properties.getTokenValidationSecret().isBlank()) {
                headers.set("X-CAS-Token-Secret", properties.getTokenValidationSecret());
            }
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("accessToken", accessToken);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(validateUrl, request, String.class);
            return parseResponse(response.getBody());
        } catch (Exception e) {
            LOGGER.error("TGT 验证失败: error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 /cas/token/validate 响应
     * <p>
     * 响应格式: { "code": 200, "data": { "userId": "xxx", "attributes": { ... } } }
     * CAS attributes 值可能是数组格式 ["value"]，提取第一个元素。
     */
    private CasUserSession parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            int code = root.path("code").asInt(-1);
            if (code != 200) {
                LOGGER.debug("TGT 验证失败: code={}, message={}", code, root.path("message").asText("未知错误"));
                return null;
            }

            JsonNode data = root.path("data");
            String userId = data.path("userId").asText();
            JsonNode attrs = data.path("attributes");

            CasUserSession session = new CasUserSession();
            session.setUserId(userId);
            session.setUsername(getAttr(attrs, "username"));
            session.setNickname(getAttr(attrs, "nickname"));
            session.setUserType(getAttr(attrs, "userType"));
            session.setPhone(getAttr(attrs, "phone"));
            session.setEmail(getAttr(attrs, "email"));
            session.setAvatarUrl(getAttr(attrs, "avatarUrl"));
            session.setIdentity(getAttr(attrs, "identity"));

            // 存储所有属性（处理 CAS attributes 数组格式）
            Map<String, String> allAttrs = new HashMap<>();
            if (attrs.isObject()) {
                attrs.fields().forEachRemaining(e -> {
                    JsonNode val = e.getValue();
                    if (val.isArray() && !val.isEmpty()) {
                        allAttrs.put(e.getKey(), val.get(0).asText());
                    } else if (!val.isNull()) {
                        allAttrs.put(e.getKey(), val.asText());
                    }
                });
            }
            session.setAttributes(allAttrs);

            return session;
        } catch (Exception e) {
            LOGGER.error("解析 TGT 验证响应失败", e);
            return null;
        }
    }

    /**
     * 从 attributes 中获取属性值（处理 CAS 数组格式）
     */
    private String getAttr(JsonNode attrs, String key) {
        JsonNode node = attrs.path(key);
        if (node.isMissingNode() || node.isNull()) return null;
        if (node.isArray() && !node.isEmpty()) return node.get(0).asText();
        return node.asText();
    }
}
