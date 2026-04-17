package org.apereo.cas.beenest.client.authentication;

import org.apereo.cas.beenest.client.session.CasUserSession;

/**
 * CAS 用户注册接口（可选）
 * <p>
 * 业务系统可实现此接口处理首次 CAS 登录的本地用户创建。
 * 如果未实现此接口，starter 使用默认会话映射和权限加载逻辑，不执行本地用户创建。
 */
public interface CasUserRegistrationService {

    /**
     * 检查本地用户是否存在
     */
    boolean userExists(String userId);

    /**
     * 是否支持自动注册
     *
     * @param session CAS 用户会话（通过 attributes.loginType 判断认证方式）
     * @return true 表示允许自动注册
     */
    boolean canAutoRegister(CasUserSession session);

    /**
     * 从 CAS 会话创建本地用户
     *
     * @param session CAS 用户会话
     * @return 本地用户 ID
     */
    String registerFromCas(CasUserSession session);
}
