package org.apereo.cas.beenest.client.accesscontrol;

import java.util.Map;
import java.util.Set;

/**
 * CAS 用户访问控制 SPI。
 * <p>
 * 下游应用实现此接口，在用户认证时根据 CAS 角色决定本地账号状态。
 * Client Starter 在以下时机调用：
 * <ul>
 *   <li>用户首次 CAS 登录（Web Session 模式）</li>
 *   <li>Bearer Token 验证/刷新时（API 模式）</li>
 * </ul>
 * <p>
 * 不实现此接口的应用保持现有行为不变（向后兼容）。
 */
public interface CasUserAccessControlService {

    /**
     * 获取本应用对应的 CAS 角色名。
     * <p>
     * 用于判断 CAS 返回的 memberOf 中是否包含本应用的访问权限。
     * 例如 "ROLE_DRONE_SYSTEM" 或 "ROLE_PAYMENT"。
     * 也可通过配置 cas.client.access-control.required-role 覆盖。
     *
     * @return CAS 角色名
     */
    String getRequiredRole();

    /**
     * 检查本地用户是否存在且可用。
     *
     * @param userId CAS 用户 ID
     * @return true 表示本地用户存在且状态正常
     */
    boolean isLocalUserActive(String userId);

    /**
     * 创建本地用户（首次授权时）。
     * <p>
     * 当 CAS 角色包含本应用权限，但本地用户不存在时调用。
     *
     * @param userId        CAS 用户 ID
     * @param casRoles      CAS 返回的所有角色
     * @param casAttributes CAS 返回的其他属性（nickname, phone, email 等）
     * @return 创建的本地用户 ID，null 表示创建失败
     */
    String createLocalUser(String userId, Set<String> casRoles,
                           Map<String, Object> casAttributes);

    /**
     * 禁用本地用户（权限撤销时）。
     * <p>
     * 当本地用户存在，但 CAS 角色不再包含本应用权限时调用。
     * 实现应：禁用账号 + 销毁该用户的所有活跃会话。
     *
     * @param userId   CAS 用户 ID
     * @param casRoles CAS 返回的所有角色（不含本应用权限）
     */
    void disableLocalUser(String userId, Set<String> casRoles);

    /**
     * 更新本地用户信息（可选）。
     * <p>
     * 当用户有权限且本地账号正常时调用，可用于同步昵称、手机号等信息。
     * 默认空实现。
     *
     * @param userId        CAS 用户 ID
     * @param casRoles      CAS 返回的所有角色
     * @param casAttributes CAS 返回的其他属性
     */
    default void updateLocalUser(String userId, Set<String> casRoles,
                                  Map<String, Object> casAttributes) {
        // 默认不更新
    }
}