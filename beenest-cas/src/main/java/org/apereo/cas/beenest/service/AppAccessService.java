package org.apereo.cas.beenest.service;

import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;

/**
 * 应用级访问控制服务（已停用，由 CAS Service Access Strategy 替代）。
 * <p>
 * 保留空壳实现以确保 Controller 编译通过。
 * 后续 Phase 会重构 Controller，届时可完全移除此类。
 */
@Slf4j
public class AppAccessService {

    /**
     * 检查用户是否有权访问某应用（始终返回 true，由 CAS 原生 Access Strategy 接管）
     */
    public boolean hasAccess(String userId, Long serviceId) {
        LOGGER.debug("访问控制委托给 CAS Service Access Strategy: userId={}, serviceId={}", userId, serviceId);
        return true;
    }

    /**
     * 授予用户访问权限（no-op）
     */
    public void grantAccess(String userId, Long serviceId, String accessLevel, String grantedBy) {
        LOGGER.debug("授权操作委托给 CAS 原生机制: userId={}, serviceId={}", userId, serviceId);
    }

    /**
     * 注册自动赋权（no-op）
     */
    public void autoGrantOnRegister(String userId, Long serviceId) {
        LOGGER.debug("自动赋权委托给 CAS 原生机制: userId={}, serviceId={}", userId, serviceId);
    }

    /**
     * 查询用户可访问的所有应用（返回空列表）
     */
    public List<Map<String, Object>> getUserApps(String userId) {
        LOGGER.debug("用户应用查询委托给 CAS 原生机制: userId={}", userId);
        return List.of();
    }
}
