package org.apereo.cas.beenest.service;

import lombok.extern.slf4j.Slf4j;

/**
 * 用户同步服务（已停用，由 CAS 协议标准属性发布替代）。
 * <p>
 * 保留空壳实现以确保 UserIdentityService 编译通过。
 * 后续 Phase 会重构 UserIdentityService，届时可完全移除此类。
 */
@Slf4j
public class UserSyncService {

    /**
     * 记录用户变更日志（no-op，由 Inspektr 审计 + CAS 协议标准替代）
     */
    public void recordChange(String userId, String changeType, Object oldValue, Object newValue) {
        LOGGER.debug("变更记录委托给 Inspektr: userId={}, type={}", userId, changeType);
    }
}
