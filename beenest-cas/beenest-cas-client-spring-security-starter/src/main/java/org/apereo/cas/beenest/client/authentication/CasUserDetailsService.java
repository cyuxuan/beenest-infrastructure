package org.apereo.cas.beenest.client.authentication;

import org.apereo.cas.client.validation.Assertion;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * CAS 用户权限加载接口
 * <p>
 * 业务系统实现此接口，从本地数据源加载用户的角色和权限。
 * CasAuthenticationProvider 验证 ST/TGT 成功后自动调用。
 * <p>
 * starter 提供默认实现；业务系统可按需覆盖以接入本地权限体系。
 * <p>
 * <b>身份契约</b>：CAS 传输的用户 ID（principal.name）是 CAS 侧账号标识，
 * 不保证与业务系统的本地用户一一对应。实现方可在此方法内做身份归一
 * （如按手机号将多个 CAS 账号归一到同一自然人），改写会话 userId 并返回
 * 归一后的 {@code CasUserDetails}。此后 Provider、访问控制、缓存与权限版本
 * 均以返回的 {@code CasUserDetails.getUserId()} 为权威身份。
 */
@FunctionalInterface
public interface CasUserDetailsService {

    /**
     * 根据 CAS Assertion 加载用户详情（含权限）
     *
     * @param userId    CAS 返回的用户 ID（CAS 侧账号标识，实现方可归一为本地统一身份）
     * @param assertion CAS Assertion（包含 CAS Server 返回的完整用户属性）
     * @return 包含角色和权限的 UserDetails，其身份为归一后的权威身份
     */
    UserDetails loadUserByCasAssertion(String userId, Assertion assertion);
}
