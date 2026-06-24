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
     * <p>
     * <b>防御性设计</b>：当 CAS 属性中完全不存在 {@code memberOf} 时（区别于存在但不含本应用角色），
     * 视为属性缺失而非权限撤销。这避免了 CAS Server 刷新端点因故未返回角色信息时
     * 误禁本地用户的问题。
     *
     * @param userId        CAS 用户 ID
     * @param casAttributes CAS 返回的属性（包含 memberOf 等多值属性）
     * @return 访问控制结果
     */
    public AccessControlResult onAuthentication(String userId, Map<String, Object> casAttributes) {
        String requiredRole = resolveRequiredRole();
        Set<String> memberOf = extractMemberOf(casAttributes);
        boolean hasAccess = memberOf.contains(requiredRole);

        // 1. 判断 memberOf 属性是否完全缺失（key 不存在），区别于"存在但不含本应用角色"
        boolean memberOfAttributeMissing = casAttributes == null || !casAttributes.containsKey("memberOf");

        log.debug("访问控制检查: userId={}, requiredRole={}, memberOf={}, hasAccess={}, memberOfMissing={}",
                userId, requiredRole, memberOf, hasAccess, memberOfAttributeMissing);

        // 2. 检查本地用户状态（异常时降级为不存在，避免因 SPI 故障阻断认证）
        boolean localExists;
        try {
            localExists = accessControlService.isLocalUserActive(userId);
        } catch (Exception e) {
            log.error("访问控制: 检查本地用户状态异常 userId={}, 降级为不存在", userId, e);
            localExists = false;
        }

        // 3. memberOf 属性缺失时的防御性处理：
        //    CAS Server 的 refresh 端点可能因实现问题未返回 memberOf 属性，
        //    此时如果本地用户已存在且活跃，应信任本地状态而非误判为权限撤销。
        //    仅当 memberOf 明确存在且不含本应用角色时，才视为权限撤销。
        if (memberOfAttributeMissing && localExists) {
            log.info("访问控制: memberOf 属性缺失但本地用户活跃，信任本地状态 userId={}", userId);
            try {
                accessControlService.updateLocalUser(userId, memberOf, casAttributes);
            } catch (Exception e) {
                log.error("访问控制: 更新本地用户信息异常 userId={}", userId, e);
            }
            return AccessControlResult.granted(userId);
        }

        // 4. 有 CAS 角色 + 无本地用户 → 自动创建
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

        // 5. 无 CAS 角色 + 有本地用户 → 自动禁用
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

        // 6. 无 CAS 角色 + 无本地用户 → 拒绝（防御性兜底）
        if (!hasAccess) {
            return AccessControlResult.denied("无访问权限");
        }

        // 7. 有 CAS 角色 + 有本地用户 → 更新信息
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
    private Set<String> extractMemberOf(Map<String, Object> casAttributes) {
        if (casAttributes == null) {
            return Collections.emptySet();
        }
        Object value = casAttributes.get("memberOf");
        switch (value) {
            // List<String> — 多值属性标准形式
            case List<?> objects -> {
                Set<String> roles = new HashSet<>();
                for (Object item : objects) {
                    if (item != null) {
                        roles.add(item.toString().trim());
                    }
                }
                return roles;
            }

            // String — 单值
            case String str when !str.isBlank() -> {
                return Set.of(str.trim());
            }

            case null, default -> {
                return Collections.emptySet();
            }
        }
    }
}
