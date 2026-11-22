package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.LoginPolicyResponse;
import club.beenest.iam.admin.dto.LoginPolicyUpdateRequest;
import club.beenest.iam.admin.dto.PasswordPolicyResponse;
import club.beenest.iam.admin.dto.PasswordPolicyUpdateRequest;

/**
 * 安全策略管理服务,维护当前域的口令策略与登录策略。
 * <p>每域一条策略行,查询与更新在策略缺失时按缺省值创建。</p>
 */
public interface SecurityPolicyService {

    /**
     * 查询当前域口令策略。
     *
     * @return 口令策略,不存在时按缺省值创建后返回
     */
    PasswordPolicyResponse getPasswordPolicy();

    /**
     * 更新当前域口令策略。
     *
     * @param request 口令策略更新参数
     * @return 更新后的口令策略
     * @throws BusinessException 最小长度大于最大长度
     */
    PasswordPolicyResponse updatePasswordPolicy(PasswordPolicyUpdateRequest request);

    /**
     * 查询当前域登录策略。
     *
     * @return 登录策略,不存在时按缺省值创建后返回
     */
    LoginPolicyResponse getLoginPolicy();

    /**
     * 更新当前域登录策略。
     * <p>登录策略带 Redis 缓存,更新后同步失效缓存。</p>
     *
     * @param request 登录策略更新参数
     * @return 更新后的登录策略
     */
    LoginPolicyResponse updateLoginPolicy(LoginPolicyUpdateRequest request);
}
