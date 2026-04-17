package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.entity.CasSyncStrategy;
import org.apereo.cas.beenest.entity.CasUserChangeLog;
import org.apereo.cas.beenest.dto.UserSyncPushFailureDTO;
import org.apereo.cas.beenest.dto.UserSyncWebhookPayloadDTO;
import org.apereo.cas.beenest.mapper.CasSyncStrategyMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用户变更推送服务
 * <p>
 * 独立于 UserSyncService，确保 @Async 通过跨 bean 调用真正生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSyncPushService {

    private final CasSyncStrategyMapper syncStrategyMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    private final RestTemplate restTemplate = createTimeoutRestTemplate();

    private static RestTemplate createTimeoutRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    @Async("beenestAsyncExecutor")
    public void pushToSubscribers(CasUserChangeLog changeLog) {
        List<CasSyncStrategy> strategies = syncStrategyMapper.selectPushEnabled();
        if (strategies == null || strategies.isEmpty()) {
            return;
        }

        for (CasSyncStrategy strategy : strategies) {
            if (!isSubscribed(strategy.getPushEvents(), changeLog.getChangeType())) {
                continue;
            }

            try {
                sendWebhook(strategy, changeLog);
            } catch (Exception e) {
                LOGGER.warn("推送失败: serviceId={}, error={}", strategy.getServiceId(), e.getMessage());
                recordPushFailure(strategy, changeLog, e.getMessage());
            }
        }
    }

    private void sendWebhook(CasSyncStrategy strategy, CasUserChangeLog changeLog) throws Exception {
        java.time.LocalDateTime createdTime = changeLog.getCreatedTime() != null
                ? changeLog.getCreatedTime()
                : java.time.LocalDateTime.now();
        UserSyncWebhookPayloadDTO payload = new UserSyncWebhookPayloadDTO();
        payload.setEventType(changeLog.getChangeType());
        payload.setUserId(changeLog.getUserId());
        payload.setTimestamp(createdTime);
        if (changeLog.getNewData() != null) {
            payload.setNewData(objectMapper.readValue(
                    changeLog.getNewData(), new TypeReference<Map<String, Object>>() {}));
        }

        String payloadJson = objectMapper.writeValueAsString(payload);

        String signature = generateSignature(payloadJson, strategy.getPushSecret());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-CAS-Signature", signature);
        headers.set("X-CAS-Event", changeLog.getChangeType());

        HttpEntity<String> request = new HttpEntity<>(payloadJson, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                strategy.getPushUrl(), HttpMethod.POST, request, String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            LOGGER.info("推送成功: serviceId={}, userId={}, type={}",
                    strategy.getServiceId(), changeLog.getUserId(), changeLog.getChangeType());
        } else {
            throw new RuntimeException("HTTP " + response.getStatusCode());
        }
    }

    private String generateSignature(String payload, String secret) {
        if (StringUtils.isBlank(secret)) {
            return "";
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            LOGGER.error("生成签名失败", e);
            return "";
        }
    }

    private boolean isSubscribed(String pushEvents, String changeType) {
        if (StringUtils.isBlank(pushEvents)) {
            return true;
        }
        return pushEvents.contains(changeType) || pushEvents.contains("*");
    }

    private void recordPushFailure(CasSyncStrategy strategy, CasUserChangeLog changeLog, String error) {
        try {
            String key = "cas:sync:push:fail:" + strategy.getServiceId() + ":" + changeLog.getId();
            UserSyncPushFailureDTO failure = new UserSyncPushFailureDTO();
            failure.setServiceId(strategy.getServiceId());
            failure.setChangeLogId(changeLog.getId());
            failure.setError(error);
            failure.setRetryCount(0);
            String value = objectMapper.writeValueAsString(failure);
            redisTemplate.opsForValue().set(key, value, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            LOGGER.error("记录推送失败信息异常", e);
        }
    }
}
