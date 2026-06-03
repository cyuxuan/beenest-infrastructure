package org.apereo.cas.beenest.client.accesscontrol;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Set;

/**
 * CAS 用户访问控制协调器。
 * <p>
 * Client Starter 内部组件，在认证成功后调用 SPI 实现进行访问控制判断。
 * 不暴露给下游应用，下游应用只需实现 {@link CasUserAccessControlService}。
 *
 * @see CasUserAccessControlService
 */
@Slf4j
@RequiredArgsConstructor
public class CasAccessControlManager {

    private final CasUserAccessControlService accessControlService;
    private final CasAccessControlProperties properties;

    /**
     * 认证成功后执行访问控制检查。
     * <p>
     * 根据 CAS 角色和本地用户状态，调用 SPI 实现进行以下操作：
     * <ol>
     *   <li>有权限 + 无本地用户 → 自动创建</li>
     *   <li>无权限 + 有本地用户 → 自动禁用</li>
     *   <li>无权限 + 无本地用户 → 拒绝访问</li>
     *   <li>有权限 + 有本地用户 → 更新信息</li>
     * </ol>
     *
     * @param userId        CAS 用户 ID
     * @param casRoles      CAS 返回的所有角色
     * @param casAttributes CAS 返回的其他属性
     * @return 访问控制结果
     */
    public AccessControlResult onAuthentication(String userId, Set<String> casRoles,
                                                 Map<String, Object> casAttributes) {
        String requiredRole = resolveRequiredRole();
        boolean hasAccess = casRoles.contains(requiredRole);

        // 1. 检查本地用户状态（异常时降级为不存在，避免因 SPI 故障阻断认证）
        boolean localExists;
        try {
            localExists = accessControlService.isLocalUserActive(userId);
        } catch (Exception e) {
            log.error("访问控制: 检查本地用户状态异常 userId={}, 降级为不存在", userId, e);
            localExists = false;
        }

        // 2. 有权限 + 无本地用户 → 自动创建
        if (hasAccess && !localExists) {
            if (properties.isAutoCreateOnGrant()) {
                try {
                    String localId = accessControlService.createLocalUser(userId, casRoles, casAttributes);
                    if (localId != null) {
                        log.info("访问控制: 自动创建本地用户 userId={}, localId={}", userId, localId);
                        return AccessControlResult.granted(localId);
                    }
                    log.warn("访问控制: 本地用户创建失败 userId={}", userId);
                    return AccessControlResult.denied("本地用户创建失败");
                } catch (Exception e) {
                    log.error("访问控制: 创建本地用户异常 userId={}", userId, e);
                    return AccessControlResult.denied("本地用户创建失败");
                }
            }
            return AccessControlResult.denied("本地用户不存在");
        }

        // 3. 无权限 + 有本地用户 → 自动禁用
        if (!hasAccess && localExists) {
            if (properties.isAutoDisableOnRevoke()) {
                try {
                    accessControlService.disableLocalUser(userId, casRoles);
                    log.info("访问控制: CAS 角色已撤销，禁用本地用户 userId={}", userId);
                } catch (Exception e) {
                    log.error("访问控制: 禁用本地用户异常 userId={}", userId, e);
                }
                return AccessControlResult.denied("访问权限已撤销");
            }
            return AccessControlResult.denied("无访问权限");
        }

        // 4. 无权限 + 无本地用户 → 拒绝（防御性兜底）
        if (!hasAccess) {
            return AccessControlResult.denied("无访问权限");
        }

        // 5. 有权限 + 有本地用户 → 更新信息
        try {
            accessControlService.updateLocalUser(userId, casRoles, casAttributes);
        } catch (Exception e) {
            log.error("访问控制: 更新本地用户信息异常 userId={}", userId, e);
            // 更新失败不影响正常访问
        }
        return AccessControlResult.granted(userId);
    }

    /**
     * 是否启用访问控制。
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 解析本应用要求的角色名。
     * <p>
     * 配置属性优先，其次 SPI 实现。
     */
    private String resolveRequiredRole() {
        if (StringUtils.hasText(properties.getRequiredRole())) {
            return properties.getRequiredRole();
        }
        return accessControlService.getRequiredRole();
    }
}