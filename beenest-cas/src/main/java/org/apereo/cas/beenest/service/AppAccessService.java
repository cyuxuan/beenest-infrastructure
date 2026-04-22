package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.entity.CasAppAccess;
import org.apereo.cas.beenest.mapper.CasAppAccessMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 应用级访问控制服务
 * <p>
 * 控制用户对特定应用的访问权限。ST 签发前通过 BeenestAccessStrategy 调用 hasAccess() 校验。
 * 支持临时授权（过期时间）、批量操作、变更日志记录。
 */
@Slf4j
@RequiredArgsConstructor
public class AppAccessService {

    private final CasAppAccessMapper appAccessMapper;
    private final UserSyncService userSyncService;

    /**
     * 检查用户是否有权访问某应用
     * <p>
     * 此方法由 BeenestAccessStrategy 在 ST 签发前调用。
     * 查询结果自动过滤已过期授权。
     *
     * @param userId    用户 ID
     * @param serviceId 服务 ID（CAS registered_service 的 id）
     * @return true=有权访问
     */
    public boolean hasAccess(String userId, Long serviceId) {
        CasAppAccess access = appAccessMapper.selectByUserAndService(userId, serviceId);
        return access != null;
    }

    /**
     * 授予用户访问权限
     *
     * @param userId      用户 ID
     * @param serviceId   服务 ID
     * @param accessLevel 访问级别
     * @param grantedBy   授权人
     */
    public void grantAccess(String userId, Long serviceId, String accessLevel, String grantedBy) {
        grantAccess(userId, serviceId, accessLevel, grantedBy, null, null);
    }

    /**
     * 授予用户访问权限（完整参数）
     *
     * @param userId      用户 ID
     * @param serviceId   服务 ID
     * @param accessLevel 访问级别
     * @param grantedBy   授权人
     * @param reason      授权原因
     * @param expireTime  过期时间（null=永久）
     */
    public void grantAccess(String userId, Long serviceId, String accessLevel,
                            String grantedBy, String reason, LocalDateTime expireTime) {
        CasAppAccess existing = appAccessMapper.selectByUserAndService(userId, serviceId);
        if (existing != null) {
            LOGGER.info("用户已有访问权限: userId={}, serviceId={}", userId, serviceId);
            return;
        }
        CasAppAccess access = new CasAppAccess();
        access.setUserId(userId);
        access.setServiceId(serviceId);
        access.setAccessLevel(accessLevel != null ? accessLevel : "BASIC");
        access.setGrantedBy(grantedBy);
        access.setReason(reason);
        access.setExpireTime(expireTime);
        appAccessMapper.insert(access);

        // 记录变更
        userSyncService.recordChange(userId, "ACCESS_GRANT", null,
                "serviceId=" + serviceId + ",level=" + accessLevel);

        LOGGER.info("授予访问权限: userId={}, serviceId={}, level={}, expire={}",
                userId, serviceId, accessLevel, expireTime);
    }

    /**
     * 撤销用户访问权限
     */
    public void revokeAccess(String userId, Long serviceId) {
        appAccessMapper.deleteByUserAndService(userId, serviceId);

        userSyncService.recordChange(userId, "ACCESS_REVOKE", null,
                "serviceId=" + serviceId);

        LOGGER.info("撤销访问权限: userId={}, serviceId={}", userId, serviceId);
    }

    /**
     * 注册自动赋权（BASIC 级别，永久有效）
     */
    public void autoGrantOnRegister(String userId, Long serviceId) {
        grantAccess(userId, serviceId, "BASIC", "SYSTEM", "注册自动赋权", null);
    }

    /**
     * 批量授权
     */
    public void batchGrantAccess(List<String> userIds, Long serviceId,
                                  String accessLevel, String grantedBy, String reason) {
        for (String userId : userIds) {
            grantAccess(userId, serviceId, accessLevel, grantedBy, reason, null);
        }
        LOGGER.info("批量授权完成: serviceId={}, count={}", serviceId, userIds.size());
    }

    /**
     * 查询用户可访问的所有应用
     */
    public List<CasAppAccess> getUserApps(String userId) {
        return appAccessMapper.selectByUserId(userId);
    }

    /**
     * 查询某应用的所有用户
     */
    public List<CasAppAccess> getAppUsers(Long serviceId) {
        return appAccessMapper.selectByServiceId(serviceId);
    }
}
