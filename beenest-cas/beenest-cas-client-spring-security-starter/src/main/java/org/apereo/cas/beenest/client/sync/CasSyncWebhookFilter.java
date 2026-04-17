package org.apereo.cas.beenest.client.sync;

import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import org.apereo.cas.beenest.client.session.ActiveSessionRegistry;
import org.apereo.cas.beenest.client.session.CasUserSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * CAS 用户同步 Webhook 过滤器
 * <p>
 * 监听 CAS Server 推送的用户变更通知（默认 POST /cas/sync/webhook），
 * 验证 HMAC-SHA256 签名后自动更新本地 Session 中的用户信息。
 */
@Slf4j
public class CasSyncWebhookFilter implements Filter {

    private final CasSecurityProperties properties;
    private final ActiveSessionRegistry activeSessionRegistry;
    private final List<CasUserChangeListener> listeners;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CasSyncWebhookFilter(CasSecurityProperties properties,
                                 ActiveSessionRegistry activeSessionRegistry,
                                 List<CasUserChangeListener> listeners) {
        this.properties = properties;
        this.activeSessionRegistry = activeSessionRegistry;
        this.listeners = listeners != null ? listeners : List.of();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String webhookPath = properties.getSync().getWebhookPath();

        // 只处理 Webhook 路径的 POST 请求（用 getServletPath 排除 contextPath）
        if (!webhookPath.equals(httpRequest.getServletPath())
                || !"POST".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // 1. 读取请求体
        String payload = readRequestBody(httpRequest);

        // 2. 验证签名
        String signature = httpRequest.getHeader("X-CAS-Signature");
        if (!verifySignature(payload, signature)) {
            LOGGER.warn("Webhook 签名验证失败: signature={}", signature);
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write("{\"code\":401,\"message\":\"签名验证失败\"}");
            return;
        }

        // 3. 解析事件
        try {
            JsonNode root = objectMapper.readTree(payload);
            String eventType = root.path("eventType").asText();
            String userId = root.path("userId").asText();
            JsonNode newDataNode = root.path("newData");
            String timestamp = root.path("timestamp").asText(null);

            if (userId.isEmpty()) {
                httpResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                httpResponse.getWriter().write("{\"code\":400,\"message\":\"userId 为空\"}");
                return;
            }

            LOGGER.info("收到用户同步 Webhook: type={}, userId={}", eventType, userId);

            // 4. 自动刷新 Session（如果启用）
            if (properties.getSync().isAutoRefreshSession()) {
                refreshUserSessions(userId, newDataNode);
            }

            // 5. 触发应用自定义的变更监听器
            if (!listeners.isEmpty()) {
                UserChangeEvent event = new UserChangeEvent();
                event.setEventType(eventType);
                event.setUserId(userId);
                event.setTimestamp(timestamp);
                if (newDataNode != null && !newDataNode.isMissingNode()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dataMap = objectMapper.convertValue(newDataNode, Map.class);
                    event.setNewData(dataMap);
                }

                for (CasUserChangeListener listener : listeners) {
                    try {
                        listener.onUserChange(event);
                    } catch (Exception e) {
                        LOGGER.error("用户变更监听器执行失败: listener={}, userId={}",
                                listener.getClass().getSimpleName(), userId, e);
                    }
                }
            }

            // 6. 返回成功
            httpResponse.setStatus(HttpServletResponse.SC_OK);
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write("{\"code\":200,\"message\":\"ok\"}");

        } catch (Exception e) {
            LOGGER.error("处理 Webhook 失败", e);
            httpResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write("{\"code\":500,\"message\":\"处理失败\"}");
        }
    }

    private void refreshUserSessions(String userId, JsonNode newData) {
        List<HttpSession> sessions = activeSessionRegistry.getSessionsByUserId(userId);
        if (sessions.isEmpty()) {
            LOGGER.debug("用户 {} 无活跃 Session，跳过刷新", userId);
            return;
        }

        for (HttpSession session : sessions) {
            try {
                Object attr = session.getAttribute(CasUserSession.SESSION_KEY);
                if (attr instanceof CasUserSession userSession) {
                    userSession.updateFromJson(newData);
                    session.setAttribute(CasUserSession.SESSION_KEY, userSession);
                    LOGGER.debug("Session 刷新成功: userId={}, sessionId={}", userId, session.getId());
                }
            } catch (IllegalStateException e) {
                LOGGER.debug("Session 已失效，跳过: userId={}", userId);
            }
        }
        LOGGER.info("用户 {} Session 刷新完成，共 {} 个", userId, sessions.size());
    }

    private boolean verifySignature(String payload, String signature) {
        if (signature == null || signature.isEmpty()) {
            LOGGER.warn("Webhook 请求缺少签名");
            return false;
        }
        String signKey = properties.getSignKey();
        if (signKey == null || signKey.isEmpty()) {
            LOGGER.error("未配置 signKey，拒绝用户同步 Webhook 请求");
            return false;
        }
        String expected = hmacSha256(payload, signKey);
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            signature.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            LOGGER.error("HMAC 计算失败", e);
            return "";
        }
    }

    private String readRequestBody(HttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
