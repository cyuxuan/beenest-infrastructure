package org.apereo.cas.beenest.client.accesscontrol;

import java.util.Map;
import java.util.Set;

/**
 * CAS 用户访问控制 SPI。
 * <p>
 * 下游应用实现此接口，在用户认证时根据 CAS {@code memberOf} 角色决定本地账号状态。
 * Client Starter 在以下时机调用：
 * <ul>
 *   <li>用户首次 CAS 登录（Web Session 模式）</li>
 *   <li>Bearer Token 验证/刷新时（API 模式）</li>
 * </ul>
 * <p>
 * <b>设计原则</b>：CAS 服务端 {@code accessStrategy.requiredAttributes={memberOf=[ROLE_DRONE_SYSTEM]}
 * 已经完成了"用户是否有权访问此应用"的判断。SPI 只负责本地用户状态同步：
 * 有 CAS 角色 → 创建/更新本地用户；无 CAS 角色 → 禁用本地用户。
 * <p>
 * 不实现此接口的应用保持现有行为不变（向后兼容）。
 */
public interface CasUserAccessControlService {

    /**
     * 获取本应用对应的 CAS 角色名。
     * <p>
     * 对应 CAS Server 中注册服务的 {@code accessStrategy.requiredAttributes.memberOf} 配置。
     * 例如 "ROLE_DRONE_SYSTEM" 或 "ROLE_PAYMENT"。
     * <p>
     * 此值用于从 CAS {@code memberOf} 属性中判断用户是否拥有本应用的访问角色，
     * 进而决定本地用户状态同步策略。
     *
     * @return CAS 角色名
     */
    String getRequiredRole();

    /**
     * 检查本地用户是否存在且可用（状态正常）。
     *
     * @param userId CAS 用户 ID
     * @return true 表示本地用户存在且状态正常
     */
    boolean isLocalUserActive(String userId);

    /**
     * 检查本地用户是否存在（不论状态）。
     * <p>
     * 与 {@link #isLocalUserActive(String)} 的区别：此方法在用户被禁用、
     * 锁定等非正常状态时也返回 true，只有用户记录不存在时才返回 false。
     * <p>
     * 默认实现回退到 {@link #isLocalUserActive(String)}，保持向后兼容。
     * 建议下游应用显式实现此方法，以区分"不存在"和"存在但禁用"两种语义。
     *
     * @param userId CAS 用户 ID
     * @return true 表示本地用户记录存在（不论状态）
     */
    default boolean isLocalUserExists(String userId) {
        return isLocalUserActive(userId);
    }

    /**
     * 创建本地用户（首次授权时）。
     * <p>
     * 当 CAS {@code memberOf} 包含本应用角色，但本地用户不存在时调用。
     * <p>
     * 如果本地用户已存在但处于禁用状态，实现应恢复用户为正常状态而非重新创建。
     *
     * @param userId        CAS 用户 ID
     * @param memberOf      CAS 返回的角色集合
     * @param casAttributes CAS 返回的其他属性（nickname, phone, email 等，多值属性为 List）
     * @return 创建的本地用户 ID，null 表示创建失败
     */
    String createLocalUser(String userId, Set<String> memberOf,
                           Map<String, Object> casAttributes);

    /**
     * 禁用本地用户（角色撤销时）。
     * <p>
     * 当本地用户存在，但 CAS {@code memberOf} 不再包含本应用角色时调用。
     * 实现应：禁用账号 + 销毁该用户的所有活跃会话。
     *
     * @param userId   CAS 用户 ID
     * @param memberOf CAS 返回的角色集合（不含本应用角色）
     */
    void disableLocalUser(String userId, Set<String> memberOf);

    /**
     * 更新本地用户信息（可选）。
     * <p>
     * 当用户有角色且本地账号正常时调用，可用于同步昵称、手机号等信息。
     * 默认空实现。
     *
     * @param userId        CAS 用户 ID
     * @param memberOf      CAS 返回的角色集合
     * @param casAttributes CAS 返回的其他属性（多值属性为 List）
     */
    default void updateLocalUser(String userId, Set<String> memberOf,
                                  Map<String, Object> casAttributes) {
        // 默认不更新
    }
}
