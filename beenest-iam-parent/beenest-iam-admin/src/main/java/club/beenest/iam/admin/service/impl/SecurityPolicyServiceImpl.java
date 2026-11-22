package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.LoginPolicyResponse;
import club.beenest.iam.admin.dto.LoginPolicyUpdateRequest;
import club.beenest.iam.admin.dto.PasswordPolicyResponse;
import club.beenest.iam.admin.dto.PasswordPolicyUpdateRequest;
import club.beenest.iam.admin.service.SecurityPolicyService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.persistence.entity.LoginPolicy;
import club.beenest.iam.persistence.entity.PasswordPolicy;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import org.springframework.stereotype.Service;

/**
 * 安全策略管理服务实现。
 * <p>策略限定在当前身份域,缺失时按缺省值创建;登录策略更新后同步失效 Redis 缓存。</p>
 */
@Service
public class SecurityPolicyServiceImpl implements SecurityPolicyService {

    private final IamSecurityPolicyManager securityPolicyManager;

    public SecurityPolicyServiceImpl(IamSecurityPolicyManager securityPolicyManager) {
        this.securityPolicyManager = securityPolicyManager;
    }

    @Override
    public PasswordPolicyResponse getPasswordPolicy() {
        PasswordPolicy policy = securityPolicyManager.ensurePasswordPolicy(
                CurrentUserHolder.require().getRealmId());
        return toResponse(policy);
    }

    @Override
    public PasswordPolicyResponse updatePasswordPolicy(PasswordPolicyUpdateRequest request) {
        if (request.minLength() > request.maxLength()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "最小长度不能大于最大长度");
        }
        PasswordPolicy policy = securityPolicyManager.ensurePasswordPolicy(
                CurrentUserHolder.require().getRealmId());
        policy.setMinLength(request.minLength());
        policy.setMaxLength(request.maxLength());
        policy.setRequireUppercase(Boolean.TRUE.equals(request.requireUppercase()));
        policy.setRequireLowercase(Boolean.TRUE.equals(request.requireLowercase()));
        policy.setRequireDigit(Boolean.TRUE.equals(request.requireDigit()));
        policy.setRequireSymbol(Boolean.TRUE.equals(request.requireSymbol()));
        policy.setHistoryCount(request.historyCount() == null ? 0 : request.historyCount());
        policy.setExpireDays(request.expireDays() == null ? 0 : request.expireDays());
        securityPolicyManager.updatePasswordPolicy(policy);
        return toResponse(securityPolicyManager.ensurePasswordPolicy(
                CurrentUserHolder.require().getRealmId()));
    }

    @Override
    public LoginPolicyResponse getLoginPolicy() {
        var principal = CurrentUserHolder.require();
        LoginPolicy policy = securityPolicyManager.ensureLoginPolicy(principal.getRealmId());
        return toResponse(policy);
    }

    @Override
    public LoginPolicyResponse updateLoginPolicy(LoginPolicyUpdateRequest request) {
        var principal = CurrentUserHolder.require();
        LoginPolicy policy = securityPolicyManager.ensureLoginPolicy(principal.getRealmId());
        policy.setMaxFailAttempts(request.maxFailAttempts());
        policy.setFailureWindowMinutes(request.failureWindowMinutes());
        policy.setLockDurationMinutes(request.lockDurationMinutes());
        policy.setCaptchaThreshold(request.captchaThreshold());
        policy.setForceMfa(request.forceMfa());
        policy.setIpMaxFailAttempts(request.ipMaxFailAttempts());
        policy.setIpLockDurationMinutes(request.ipLockDurationMinutes());
        policy.setSmsLoginEnabled(request.smsLoginEnabled());
        policy.setSmsCodeTtlSeconds(request.smsCodeTtlSeconds());
        policy.setSmsMaxSendPerHour(request.smsMaxSendPerHour());
        policy.setSmsMaxVerifyAttempts(request.smsMaxVerifyAttempts());
        policy.setMaxConcurrentSessions(request.maxConcurrentSessions());
        securityPolicyManager.updateLoginPolicy(policy);
        // 登录策略有 Redis 缓存,更新后必须失效
        securityPolicyManager.evictLoginPolicyCache(principal.getRealmKey());
        return toResponse(securityPolicyManager.ensureLoginPolicy(principal.getRealmId()));
    }

    /**
     * 口令策略实体转响应。
     *
     * @param policy 口令策略
     * @return 口令策略响应
     */
    private PasswordPolicyResponse toResponse(PasswordPolicy policy) {
        return new PasswordPolicyResponse(policy.getMinLength(), policy.getMaxLength(),
                policy.getRequireUppercase(), policy.getRequireLowercase(), policy.getRequireDigit(),
                policy.getRequireSymbol(), policy.getHistoryCount(), policy.getExpireDays());
    }

    /**
     * 登录策略实体转响应。
     *
     * @param policy 登录策略
     * @return 登录策略响应
     */
    private LoginPolicyResponse toResponse(LoginPolicy policy) {
        return new LoginPolicyResponse(policy.getMaxFailAttempts(), policy.getFailureWindowMinutes(),
                policy.getLockDurationMinutes(), policy.getCaptchaThreshold(), policy.getForceMfa(),
                policy.getIpMaxFailAttempts(), policy.getIpLockDurationMinutes(),
                policy.getSmsLoginEnabled(), policy.getSmsCodeTtlSeconds(),
                policy.getSmsMaxSendPerHour(), policy.getSmsMaxVerifyAttempts(),
                policy.getMaxConcurrentSessions());
    }
}
