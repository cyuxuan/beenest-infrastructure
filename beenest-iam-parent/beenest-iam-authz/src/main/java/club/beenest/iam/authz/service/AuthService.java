package club.beenest.iam.authz.service;

import club.beenest.iam.authz.principal.IamUserPrincipal;

/**
 * 认证服务。
 * <p>提供 REST 登录与全端下线能力;表单登录与 REST 登录共用同一认证流程,
 * 因此失败计数、账号状态与权限计算只有一份实现。</p>
 */
public interface AuthService {

    /**
     * 账号密码认证。
     * <p>身份域取自当前请求上下文,无上下文时回落默认域。
     * 认证失败抛出 Spring Security 的 AuthenticationException,由全局异常处理器转业务码;
     * 需要多因子认证时抛出 {@link club.beenest.iam.authz.authentication.MfaRequiredException}。</p>
     *
     * @param username 登录名
     * @param password 明文口令
     * @return 认证通过后的用户主体(含权限码)
     */
    IamUserPrincipal authenticate(String username, String password);

    /**
     * MFA 第二步完成后重建主体。
     * <p>在口令已通过、动态口令/恢复码校验通过后调用,
     * 重新加载域与用户并构建主体(与口令直登路径保持同一套权限计算)。</p>
     *
     * @param realmKey  身份域标识
     * @param userId    用户主键
     * @param loginType 第一因素认证方式(来自票据);未知传 null
     * @return 认证通过后的用户主体(含权限码)
     */
    IamUserPrincipal completeMfaLogin(String realmKey, Long userId, String loginType);

    /**
     * 全端下线:递增令牌版本使该用户已签发的全部令牌失效,并结束其全部活跃会话。
     *
     * @param realmId  身份域主键
     * @param realmKey 身份域标识(用于清理权限缓存键)
     * @param userId   用户主键
     * @return 被结束的会话数量
     */
    int logoutAll(Long realmId, String realmKey, Long userId);
}
