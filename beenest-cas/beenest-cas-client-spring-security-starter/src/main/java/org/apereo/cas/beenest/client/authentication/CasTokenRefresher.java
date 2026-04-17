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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Token 刷新器
 * <p>
 * 调用 CAS Server 的 refresh 端点，使用 refreshToken 换取新的 accessToken + refreshToken。
 * 用于无感刷新场景：当 accessToken (TGT) 过期时，Client Starter 自动调用此刷新器。
 * <p>
 * 使用带超时的 RestTemplate，防止 CAS Server 响应慢导致业务请求阻塞。
 */
@Slf4j
public class CasTokenRefresher {

    private final CasSecurityProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CasTokenRefresher(CasSecurityProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTokenAuth().getRefreshTimeoutMs());
        factory.setReadTimeout(properties.getTokenAuth().getRefreshTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 使用 refreshToken 调用 CAS Server 刷新端点
     *
     * @param refreshToken 刷新令牌
     * @return 刷新结果，包含新的 accessToken、refreshToken 和用户信息；失败返回 null
     */
    public TokenRefreshResult refreshToken(String refreshToken) {
        String url = properties.getServerUrl() + properties.getTokenAuth().getRefreshEndpoint();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = new HashMap<>();
            body.put("refreshToken", refreshToken);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            return parseResponse(response.getBody());
        } catch (Exception e) {
            LOGGER.warn("Token 刷新请求失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 CAS Server refresh 端点响应
     * <p>
     * 响应格式:
     * <pre>
     * {
     *   "code": 200,
     *   "data": {
     *     "accessToken": "TGT-xxx",
     *     "refreshToken": "uuid",
     *     "expiresIn": 604800,
     *     "userId": "Uxxx",
     *     "attributes": { ... }
     *   }
     * }
     * </pre>
     */
    private TokenRefreshResult parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            int code = root.path("code").asInt(-1);
            if (code != 200) {
                LOGGER.debug("Token 刷新失败: code={}, message={}",
                        code, root.path("message").asText("未知错误"));
                return null;
            }

            JsonNode data = root.path("data");
            String newAccessToken = data.path("accessToken").asText(null);
            String newRefreshToken = data.path("refreshToken").asText(null);
            String userId = data.path("userId").asText(null);
            long expiresIn = data.path("expiresIn").asLong(0);

            if (newAccessToken == null || userId == null) {
                LOGGER.warn("Token 刷新响应缺少必要字段");
                return null;
            }

            // 构建用户会话信息
            CasUserSession session = new CasUserSession();
            session.setUserId(userId);
            session.setAuthTime(System.currentTimeMillis());

            // 解析 attributes
            JsonNode attrs = data.path("attributes");
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
            session.setUsername(getAttr(attrs, "username"));
            session.setNickname(getAttr(attrs, "nickname"));
            session.setUserType(getAttr(attrs, "userType"));
            session.setPhone(getAttr(attrs, "phone"));
            session.setEmail(getAttr(attrs, "email"));
            session.setAvatarUrl(getAttr(attrs, "avatarUrl"));
            session.setIdentity(getAttr(attrs, "identity"));

            return new TokenRefreshResult(newAccessToken, newRefreshToken, expiresIn, session);
        } catch (Exception e) {
            LOGGER.error("解析 Token 刷新响应失败", e);
            return null;
        }
    }

    private String getAttr(JsonNode attrs, String key) {
        JsonNode node = attrs.path(key);
        if (node.isMissingNode() || node.isNull()) return null;
        if (node.isArray() && !node.isEmpty()) return node.get(0).asText();
        return node.asText();
    }

    /**
     * Token 刷新结果
     */
    public static class TokenRefreshResult {
        private final String newAccessToken;
        private final String newRefreshToken;
        private final long expiresIn;
        private final CasUserSession session;

        public TokenRefreshResult(String newAccessToken, String newRefreshToken,
                                   long expiresIn, CasUserSession session) {
            this.newAccessToken = newAccessToken;
            this.newRefreshToken = newRefreshToken;
            this.expiresIn = expiresIn;
            this.session = session;
        }

        public String getNewAccessToken() { return newAccessToken; }
        public String getNewRefreshToken() { return newRefreshToken; }
        public long getExpiresIn() { return expiresIn; }
        public CasUserSession getSession() { return session; }
    }
}
