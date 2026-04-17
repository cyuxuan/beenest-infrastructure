package org.apereo.cas.beenest.controller;

import org.apereo.cas.beenest.common.exception.BusinessException;
import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.entity.CasUserChangeLog;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.beenest.service.UserSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 用户同步 API
 * <p>
 * 供应用服务调用，拉取用户信息和变更日志。
 * 请求头需包含 X-CAS-Timestamp 和 X-CAS-Signature 用于签名验证。
 * 签名算法：HMAC-SHA256(timestamp, signKey)
 * <p>
 * 时间戳校验：允许最大偏移 5 分钟，防止重放攻击。
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserSyncController {

    private final UnifiedUserMapper userMapper;
    private final UserSyncService userSyncService;
    private final StringRedisTemplate redisTemplate;

    /** 签名密钥，从配置中读取 */
    @Value("${beenest.sync.signKey:}")
    private String signKey;

    /** 时间戳最大偏移（毫秒），防止重放攻击 */
    private static final long MAX_TIMESTAMP_DRIFT_MS = 5 * 60 * 1000;

    /**
     * 获取用户信息
     */
    @GetMapping("/{userId}")
    public R<UnifiedUserDO> getUser(
            @PathVariable String userId,
            @RequestHeader(value = "X-CAS-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-CAS-Signature", required = false) String signature) {
        verifyRequest(timestamp, signature);

        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            return R.fail(404, "用户不存在");
        }
        // 脱敏：清除密码和 MFA 密钥
        user.setPasswordHash(null);
        user.setMfaSecretEncrypted(null);
        return R.ok(user);
    }

    /**
     * 批量获取用户信息
     * <p>
     * 使用 SQL IN 批量查询，避免 N+1 问题。
     */
    @PostMapping("/batch")
    public R<List<UnifiedUserDO>> batchGetUsers(
            @RequestBody Map<String, List<String>> request,
            @RequestHeader(value = "X-CAS-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-CAS-Signature", required = false) String signature) {
        verifyRequest(timestamp, signature);

        List<String> userIds = request.get("userIds");
        if (userIds == null || userIds.isEmpty()) {
            return R.fail(400, "userIds 不能为空");
        }
        if (userIds.size() > 200) {
            return R.fail(400, "单次最多查询 200 个用户");
        }

        // 使用批量查询替代 N+1
        List<UnifiedUserDO> users = userMapper.selectByUserIds(userIds);
        if (users == null) {
            return R.ok(List.of());
        }
        users.forEach(u -> {
            u.setPasswordHash(null);
            u.setMfaSecretEncrypted(null);
        });
        return R.ok(users);
    }

    /**
     * 拉取变更日志
     */
    @GetMapping("/changes")
    public R<List<CasUserChangeLog>> getChanges(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = "X-CAS-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-CAS-Signature", required = false) String signature) {
        verifyRequest(timestamp, signature);
        return R.ok(userSyncService.getChangeLog(since, limit));
    }

    /**
     * 确认变更已同步
     */
    @PostMapping("/changes/synced")
    public R<Void> markSynced(
            @RequestBody Map<String, List<Long>> request,
            @RequestHeader(value = "X-CAS-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-CAS-Signature", required = false) String signature) {
        verifyRequest(timestamp, signature);
        List<Long> ids = request.get("ids");
        userSyncService.markSynced(ids);
        return R.ok();
    }

    /**
     * 校验请求签名和时间戳
     * <p>
     * 1. 校验时间戳在允许偏移范围内（防重放）
     * 2. 使用 HMAC-SHA256 校验签名
     */
    private void verifyRequest(String timestamp, String signature) {
        if (StringUtils.isBlank(signKey)) {
            throw new BusinessException(500, "用户同步签名密钥未配置");
        }
        if (timestamp == null || signature == null) {
            throw new BusinessException(401, "缺少签名头 X-CAS-Timestamp 或 X-CAS-Signature");
        }

        // 1. 时间戳校验（防重放攻击）
        try {
            long requestTime = Long.parseLong(timestamp);
            long now = System.currentTimeMillis();
            long drift = Math.abs(now - requestTime);
            if (drift > MAX_TIMESTAMP_DRIFT_MS) {
                throw new BusinessException(401, "请求时间戳超出允许范围");
            }
        } catch (NumberFormatException e) {
            throw new BusinessException(401, "时间戳格式无效");
        }

        // 2. 签名校验
        if (!userSyncService.verifySignature(timestamp, signature, signKey)) {
            throw new BusinessException(401, "签名验证失败");
        }

        String replayKey = "cas:sync:replay:" + signature;
        Boolean firstSeen = redisTemplate.opsForValue().setIfAbsent(replayKey, "1", Duration.ofMinutes(5));
        if (!Boolean.TRUE.equals(firstSeen)) {
            throw new BusinessException(401, "请求已重复");
        }
    }
}
