package org.apereo.cas.beenest.client.accesscontrol;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CAS 用户访问控制协调器。
 * <p>
 * Client Starter 内部组件，在认证成功后根据 CAS 返回的 {@code memberOf} 属性
 * 同步本地用户状态（创建/禁用/更新）。
 * <p>
 * <b>设计原则</b>：CAS 服务端的 {@code accessStrategy.requiredAttributes} 已经完成了
 * "用户是否有权访问此应用"的判断。如果请求成功到达客户端，说明 CAS 服务端已校验通过。
 * 本组件不再重复校验访问权限，而是专注于本地用户状态同步：
 * <ul>
 *   <li>有 CAS 角色 + 无本地用户 → 自动创建</li>
 *   <li>无 CAS 角色 + 有本地用户 → 自动禁用</li>
 *   <li>有 CAS 角色 + 有本地用户 → 更新信息</li>
 *   <li>无 CAS 角色 + 无本地用户 → 拒绝访问（防御性兜底）</li>
 * </ul>
 *
 * @see CasUserAccessControlService
 */
@Slf4j
@RequiredArgsConstructor
public class CasAccessControlManager {

    private final CasUserAccessControlService accessControlService;
    private final CasAccessControlProperties properties;

    /**
     * 认证成功后执行访问控制同步。
     * <p>
     * 从 CAS 返回的 {@code casAttributes} 中提取 {@code memberOf} 角色列表，
     * 判断用户是否拥有本应用的访问角色，并据此同步本地用户状态。
     *
     * @param userId        CAS 用户 ID
     * @param casAttributes CAS 返回的属性（包含 memberOf 等多值属性）
     * @return 访问控制结果
     */
    public AccessControlResult onAuthentication(String userId, Map<String, Object> casAttributes) {
        String requiredRole = resolveRequiredRole();
        Set<String> memberOf = extractMemberOf(casAttributes);
        boolean hasAccess = memberOf.contains(requiredRole);

        log.debug("访问控制检查: userId={}, requiredRole={}, memberOf={}, hasAccess={}",
                userId, requiredRole, memberOf, hasAccess);

        // 1. 检查本地用户状态（异常时降级为不存在，避免因 SPI 故障阻断认证）
        boolean localExists;
        try {
            localExists = accessControlService.isLocalUserActive(userId);
        } catch (Exception e) {
            log.error("访问控制: 检查本地用户状态异常 userId={}, 降级为不存在", userId, e);
            localExists = false;
        }

        // 2. 有 CAS 角色 + 无本地用户 → 自动创建
        if (hasAccess && !localExists) {
            if (properties.isAutoCreateOnGrant()) {
                try {
                    String localId = accessControlService.createLocalUser(userId, memberOf, casAttributes);
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

        // 3. 无 CAS 角色 + 有本地用户 → 自动禁用
        if (!hasAccess && localExists) {
            if (properties.isAutoDisableOnRevoke()) {
                try {
                    accessControlService.disableLocalUser(userId, memberOf);
                    log.info("访问控制: CAS 角色已撤销，禁用本地用户 userId={}", userId);
                } catch (Exception e) {
                    log.error("访问控制: 禁用本地用户异常 userId={}", userId, e);
                }
                return AccessControlResult.denied("访问权限已撤销");
            }
            return AccessControlResult.denied("无访问权限");
        }

        // 4. 无 CAS 角色 + 无本地用户 → 拒绝（防御性兜底）
        if (!hasAccess) {
            return AccessControlResult.denied("无访问权限");
        }

        // 5. 有 CAS 角色 + 有本地用户 → 更新信息
        try {
            accessControlService.updateLocalUser(userId, memberOf, casAttributes);
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
     * 解析本应用要求的 CAS 角色名。
     * <p>
     * 统一从 SPI 实现获取，不再支持配置覆盖。
     * requiredRole 是 CAS 服务端 accessStrategy 中配置的角色名，
     * 只有 SPI 实现者知道本应用对应哪个 CAS 角色。
     */
    private String resolveRequiredRole() {
        return accessControlService.getRequiredRole();
    }

    /**
     * 从 CAS 属性中提取 memberOf 角色集合。
     * <p>
     * CAS 返回的 memberOf 属性可能是以下类型之一：
     * <ul>
     *   <li>{@code List<String>} — 多值属性的标准形式</li>
     *   <li>{@code String} — 单值情况</li>
     * </ul>
     *
     * @param casAttributes CAS 属性
     * @return 角色集合
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractMemberOf(Map<String, Object> casAttributes) {
        if (casAttributes == null) {
            return Collections.emptySet();
        }
        Object value = casAttributes.get("memberOf");
        if (value == null) {
            return Collections.emptySet();
        }

        // List<String> — 多值属性标准形式
        if (value instanceof List<?>) {
            Set<String> roles = new HashSet<>();
            for (Object item : (List<?>) value) {
                if (item != null) {
                    roles.add(item.toString().trim());
                }
            }
            return roles;
        }

        // String — 单值
        if (value instanceof String str && !str.isBlank()) {
            return Set.of(str.trim());
        }

        return Collections.emptySet();
    }
}
