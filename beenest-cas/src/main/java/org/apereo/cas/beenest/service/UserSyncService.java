package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.entity.CasUserChangeLog;
import org.apereo.cas.beenest.mapper.CasUserChangeLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * 用户同步服务
 * <p>
 * 支持两种同步模式：
 * 1. 推送模式（Webhook）：用户变更时通过 {@code @Async} 异步通知已订阅的应用服务
 * 2. 拉取模式：应用主动查询变更日志
 * <p>
 * 同步策略通过 cas_sync_strategy 表配置，每个应用可独立配置。
 * Webhook 推送使用带超时的 RestTemplate（连接 5 秒、读取 10 秒），
 * 并在独立线程池中执行，不阻塞主认证流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSyncService {

    private final CasUserChangeLogMapper changeLogMapper;
    private final ObjectMapper objectMapper;
    private final UserSyncPushService pushService;

    /**
     * 记录用户变更并触发推送
     *
     * @param userId     用户 ID
     * @param changeType 变更类型（CREATE/UPDATE/DELETE/STATUS_CHANGE）
     * @param oldData    变更前数据
     * @param newData    变更后数据
     */
    public void recordChange(String userId, String changeType, Object oldData, Object newData) {
        try {
            // 1. 记录变更日志
            CasUserChangeLog changeLog = new CasUserChangeLog();
            changeLog.setUserId(userId);
            changeLog.setChangeType(changeType);
            changeLog.setOldData(oldData != null ? objectMapper.writeValueAsString(oldData) : null);
            changeLog.setNewData(newData != null ? objectMapper.writeValueAsString(newData) : null);
            changeLog.setSynced(false);
            changeLog.setCreatedTime(LocalDateTime.now());
            changeLogMapper.insert(changeLog);

            // 2. 异步推送给已订阅的应用
            pushService.pushToSubscribers(changeLog);

        } catch (Exception e) {
            LOGGER.error("记录用户变更失败: userId={}, type={}", userId, changeType, e);
        }
    }

    /**
     * 查询未同步的变更日志（供应用服务拉取）
     *
     * @param since 起始时间
     * @param limit 最大条数
     * @return 变更日志列表
     */
    public List<CasUserChangeLog> getChangeLog(LocalDateTime since, int limit) {
        return changeLogMapper.selectUnsynced(since, limit);
    }

    /**
     * 标记变更已同步
     */
    public void markSynced(List<Long> ids) {
        if (ids != null && !ids.isEmpty()) {
            changeLogMapper.markSynced(ids);
        }
    }

    /**
     * 校验 signKey 签名（供拉取 API 使用）
     *
     * @param timestamp 请求时间戳
     * @param signature 签名
     * @param secret    签名密钥
     * @return 是否有效
     */
    public boolean verifySignature(String timestamp, String signature, String secret) {
        if (StringUtils.isBlank(secret) || StringUtils.isBlank(signature)) {
            return false;
        }
        String expected = generateSignature(timestamp, secret);
        return expected.equals(signature);
    }

    private String generateSignature(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            LOGGER.error("生成签名失败", e);
            return "";
        }
    }
}
