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
     * <b>决策矩阵</b>（3 × 3，取代旧的 2 × 2）：
     * <pre>
     *                      │ 本地不存在  │ 本地存在但禁用 │ 本地活跃
     * ─────────────────────┼────────────┼───────────────┼──────────
     *  有 CAS 角色          │ 创建       │ 恢复+更新     │ 更新
     *  无 CAS 角色          │ 拒绝       │ 保持禁用      │ 禁用
     *  memberOf 属性缺失    │ 信任放行   │ 信任放行      │ 信任放行
     * </pre>
     * <p>
     * <b>关键设计</b>：
     * <ul>
     *   <li>通过 {@link CasUserAccessControlService#isLocalUserExists} 区分"不存在"与"存在但禁用"，
     *       避免把被禁用的用户误判为新用户导致矛盾日志</li>
     *   <li>当 memberOf 属性完全缺失时（CAS refresh 端点未返回角色），
     *       信任本地状态不做任何禁用操作，防止误禁</li>
     *   <li>有 CAS 角色 + 本地用户被禁用 → 恢复为正常状态（而非走创建流程）</li>
     * </ul>
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

        // 2. 检查本地用户状态，区分三态：不存在 / 存在但禁用 / 活跃
        //    - isLocalUserExists: 记录是否存在（不论状态）
        //    - isLocalUserActive: 是否正常可用
        boolean localExists;
        boolean localActive;
        try {
            localExists = accessControlService.isLocalUserExists(userId);
            localActive = localExists && accessControlService.isLocalUserActive(userId);
        } catch (Exception e) {
            log.error("访问控制: 检查本地用户状态异常 userId={}, 降级为不存在", userId, e);
            localExists = false;
            localActive = false;
        }

        log.debug("访问控制检查: userId={}, requiredRole={}, memberOf={}, hasAccess={}, memberOfMissing={}, localExists={}, localActive={}",
                userId, requiredRole, memberOf, hasAccess, memberOfAttributeMissing, localExists, localActive);

        // 3. memberOf 属性缺失时的防御性处理：
        //    CAS Server 的 refresh 端点可能因实现问题未返回 memberOf 属性，
        //    此时如果本地用户已存在，应信任本地状态而非误判为权限撤销。
        //    仅当 memberOf 明确存在且不含本应用角色时，才视为权限撤销。
        if (memberOfAttributeMissing && localExists) {
            log.info("访问控制: memberOf 属性缺失但本地用户存在，信任本地状态 userId={}, localActive={}", userId, localActive);
            if (localActive) {
                try {
                    accessControlService.updateLocalUser(userId, memberOf, casAttributes);
                } catch (Exception e) {
                    log.error("访问控制: 更新本地用户信息异常 userId={}", userId, e);
                }
            }
            return AccessControlResult.granted(userId);
        }

        // 4. memberOf 属性缺失 + 本地用户也不存在 → 信任放行
        //    此时无法判断权限，但既然 CAS 已认证通过（serviceRegistry 的 accessStrategy 已放行），
        //    不应因 memberOf 缺失而拒绝首次访问
        if (memberOfAttributeMissing && !localExists) {
            log.info("访问控制: memberOf 属性缺失且本地无用户，信任 CAS 认证放行 userId={}", userId);
            if (properties.isAutoCreateOnGrant()) {
                try {
                    String localId = accessControlService.createLocalUser(userId, memberOf, casAttributes);
                    if (localId != null) {
                        log.info("访问控制: memberOf 缺失时自动创建本地用户 userId={}", userId);
                        return AccessControlResult.granted(localId);
                    }
                } catch (Exception e) {
                    log.error("访问控制: memberOf 缺失时创建本地用户异常 userId={}", userId, e);
                }
            }
            return AccessControlResult.granted(userId);
        }

        // 5. 有 CAS 角色 + 本地用户不存在 → 自动创建
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

        // 6. 有 CAS 角色 + 本地用户存在但被禁用 → 恢复 + 更新
        if (hasAccess && localExists && !localActive) {
            try {
                // 复用 createLocalUser 恢复禁用用户（SPI 实现内部会检测已存在用户并恢复）
                String localId = accessControlService.createLocalUser(userId, memberOf, casAttributes);
                if (localId != null) {
                    log.info("访问控制: CAS 角色已恢复，重新启用本地用户 userId={}", userId);
                    return AccessControlResult.granted(localId);
                }
                // createLocalUser 返回 null 说明恢复失败，降级为更新
                log.warn("访问控制: 恢复禁用用户失败，尝试更新 userId={}", userId);
                accessControlService.updateLocalUser(userId, memberOf, casAttributes);
            } catch (Exception e) {
                log.error("访问控制: 恢复禁用用户异常 userId={}", userId, e);
            }
            return AccessControlResult.granted(userId);
        }

        // 7. 无 CAS 角色 + 本地用户活跃 → 自动禁用
        if (!hasAccess && localActive) {
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

        // 8. 无 CAS 角色 + 本地用户已被禁用 → 保持禁用，拒绝访问
        if (!hasAccess && localExists && !localActive) {
            log.info("访问控制: CAS 角色缺失且本地用户已禁用，保持现状 userId={}", userId);
            return AccessControlResult.denied("访问权限已撤销");
        }

        // 9. 无 CAS 角色 + 无本地用户 → 拒绝（防御性兜底）
        if (!hasAccess) {
            return AccessControlResult.denied("无访问权限");
        }

        // 10. 有 CAS 角色 + 本地用户活跃 → 更新信息
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
