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
 */
@FunctionalInterface
public interface CasUserDetailsService {

    /**
     * 根据 CAS Assertion 加载用户详情（含权限）
     *
     * @param userId    CAS 返回的用户 ID
     * @param assertion CAS Assertion（包含 CAS Server 返回的完整用户属性）
     * @return 包含角色和权限的 UserDetails
     */
    UserDetails loadUserByCasAssertion(String userId, Assertion assertion);
}
