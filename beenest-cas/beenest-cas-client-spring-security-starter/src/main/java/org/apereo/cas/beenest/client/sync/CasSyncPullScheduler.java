package org.apereo.cas.beenest.client.sync;

import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import org.apereo.cas.beenest.client.session.ActiveSessionRegistry;
import org.apereo.cas.beenest.client.session.CasUserSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 用户同步拉取调度器（已弃用）
 * <p>
 * 定时调用 CAS Server {@code /api/user/changes} 拉取变更日志。
 * <p>
 * <b>已弃用</b>：CAS Server 全面原生化重构后已移除 {@code /api/user/changes} 端点。
 * 替代方案：每次 Bearer Token 验证时 CAS 返回最新用户属性，无需额外同步通道。
 * 如需感知用户变更，请使用 {@link CasUserChangeListener} 配合 OIDC UserInfo 端点。
 *
 * @deprecated CAS Server 已移除 pull 端点，此功能不再可用。将在下个大版本移除。
 */
@Slf4j
@Deprecated(since = "2.0", forRemoval = true)
public class CasSyncPullScheduler {

    private final CasSecurityProperties properties;
    private final ActiveSessionRegistry activeSessionRegistry;
    private final List<CasUserChangeListener> listeners;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile String lastPullTimestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);

    public CasSyncPullScheduler(CasSecurityProperties properties,
                                 ActiveSessionRegistry activeSessionRegistry,
                                 List<CasUserChangeListener> listeners) {
        this.properties = properties;
        this.activeSessionRegistry = activeSessionRegistry;
        this.listeners = listeners != null ? listeners : List.of();
        LOGGER.warn("【已弃用】CasSyncPullScheduler 启用 — CAS Server 已移除 /api/user/changes 端点，拉取请求将失败。" +
                "请迁移至 CAS 原生属性发布机制（每次 token 验证返回最新属性），并在下个大版本前移除 sync.pull-enabled=true 配置。");
    }

    @Scheduled(fixedDelayString = "${cas.client.sync.pull-interval-seconds:60}000")
    public void pullChanges() {
        if (properties.getSync().getPullIntervalSeconds() <= 0) {
            return;
        }

        if (properties.getSignKey() == null || properties.getSignKey().isEmpty()) {
            LOGGER.error("未配置 signKey，跳过用户变更拉取");
            return;
        }

        try {
            String url = properties.getServerUrl() + "/api/user/changes"
                    + "?since=" + lastPullTimestamp
                    + "&limit=100";

            String timestamp = String.valueOf(System.currentTimeMillis());
            String signature = hmacSha256(timestamp, properties.getSignKey());

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-CAS-Timestamp", timestamp);
            headers.set("X-CAS-Signature", signature);
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                LOGGER.warn("拉取变更日志失败: status={}", response.getStatusCode());
                return;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode dataNode = root.path("data");

            if (!dataNode.isArray() || dataNode.isEmpty()) {
                return;
            }

            LOGGER.info("拉取到 {} 条变更", dataNode.size());

            for (JsonNode changeLog : dataNode) {
                try {
                    processChange(changeLog);
                } catch (Exception e) {
                    LOGGER.error("处理变更记录失败: id={}", changeLog.path("id").asText(), e);
                }
            }

        } catch (Exception e) {
            LOGGER.error("拉取用户变更异常", e);
        }
    }

    private void processChange(JsonNode changeLog) {
        String userId = changeLog.path("userId").asText();
        String changeType = changeLog.path("changeType").asText();
        String newDataStr = changeLog.path("newData").asText(null);

        LOGGER.debug("处理变更: userId={}, type={}", userId, changeType);

        if (properties.getSync().isAutoRefreshSession() && newDataStr != null) {
            try {
                JsonNode newData = objectMapper.readTree(newDataStr);
                List<HttpSession> sessions = activeSessionRegistry.getSessionsByUserId(userId);
                for (HttpSession session : sessions) {
                    try {
                        Object attr = session.getAttribute(CasUserSession.SESSION_KEY);
                        if (attr instanceof CasUserSession userSession) {
                            userSession.updateFromJson(newData);
                            session.setAttribute(CasUserSession.SESSION_KEY, userSession);
                        }
                    } catch (IllegalStateException ignored) {
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("刷新 Session 失败: userId={}", userId, e);
            }
        }

        if (!listeners.isEmpty() && newDataStr != null) {
            UserChangeEvent event = new UserChangeEvent();
            event.setEventType(changeType);
            event.setUserId(userId);
            event.setTimestamp(changeLog.path("createdTime").asText(null));
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> dataMap = objectMapper.readValue(newDataStr, Map.class);
                event.setNewData(dataMap);
            } catch (Exception ignored) {}

            for (CasUserChangeListener listener : listeners) {
                try {
                    listener.onUserChange(event);
                } catch (Exception e) {
                    LOGGER.error("监听器执行失败: {}", listener.getClass().getSimpleName(), e);
                }
            }
        }
    }

    private String hmacSha256(String data, String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return "";
        }
    }
}
