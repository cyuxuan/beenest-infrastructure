package club.beenest.iam.authz.security;

import club.beenest.iam.authz.notification.SecurityAlertService;
import club.beenest.iam.authz.requiredaction.RequiredActionService;
import club.beenest.iam.core.model.RequiredAction;
import club.beenest.iam.persistence.entity.PasswordPolicy;
import club.beenest.iam.persistence.manager.IamPasswordHistoryManager;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一改密服务。
 * <p>管理端建号/重置口令与用户自助改密共用:策略校验(复杂度 + 历史)、停用旧凭证写入新凭证、
 * 记录口令历史、令牌版本递增,既有登录态全部失效。</p>
 * <p>自助改密完成后摘除"必须改密"待办;管理员重置后要求用户下次登录改密。</p>
 * <p>三条改密入口(自助、找回链接、管理员重置)都收敛到本类,因此凭证变更提醒也在这里统一
 * 触发:口令被改这件事本身就足以成为账号被接管的信号,不区分是谁发起的。</p>
 */
@Service
public class PasswordChangeService {

    private final IamUserManager userManager;
    private final IamSecurityPolicyManager securityPolicyManager;
    private final PasswordPolicyService passwordPolicyService;
    private final IamPasswordHistoryManager passwordHistoryManager;
    private final PasswordEncoder passwordEncoder;
    private final RequiredActionService requiredActionService;
    private final SecurityAlertService securityAlertService;

    public PasswordChangeService(IamUserManager userManager,
                                 IamSecurityPolicyManager securityPolicyManager,
                                 PasswordPolicyService passwordPolicyService,
                                 IamPasswordHistoryManager passwordHistoryManager,
                                 PasswordEncoder passwordEncoder,
                                 RequiredActionService requiredActionService,
                                 SecurityAlertService securityAlertService) {
        this.userManager = userManager;
        this.securityPolicyManager = securityPolicyManager;
        this.passwordPolicyService = passwordPolicyService;
        this.passwordHistoryManager = passwordHistoryManager;
        this.passwordEncoder = passwordEncoder;
        this.requiredActionService = requiredActionService;
        this.securityAlertService = securityAlertService;
    }

    /**
     * 用户自助改密:改完清掉"必须改密"待办。
     *
     * @param realmId     身份域主键
     * @param userId      用户主键
     * @param rawPassword 新口令明文
     * @param sid         当前会话标识;为空时只改库
     * @throws BusinessException 口令不符合域策略或与历史口令重复
     */
    @Transactional
    public void changePassword(Long realmId, Long userId, String rawPassword, String sid) {
        apply(realmId, userId, rawPassword);
        // 口令是用户自己设的,待办已满足:同步摘除库与当前会话上的标记
        requiredActionService.complete(sid, userId, RequiredAction.UPDATE_PASSWORD);
    }

    /**
     * 用户自助改密(无会话上下文)。
     *
     * @param realmId     身份域主键
     * @param userId      用户主键
     * @param rawPassword 新口令明文
     * @throws BusinessException 口令不符合域策略或与历史口令重复
     */
    @Transactional
    public void changePassword(Long realmId, Long userId, String rawPassword) {
        changePassword(realmId, userId, rawPassword, null);
    }

    /**
     * 管理员重置口令:重置后强制用户下次登录改密。
     *
     * @param realmId     身份域主键
     * @param userId      用户主键
     * @param rawPassword 新口令明文
     * @throws BusinessException 口令不符合域策略或与历史口令重复
     */
    @Transactional
    public void resetPassword(Long realmId, Long userId, String rawPassword) {
        apply(realmId, userId, rawPassword);
        requiredActionService.requirePasswordChange(userId);
    }

    /**
     * 策略校验、写入新凭证并记录口令历史。
     *
     * @param realmId     身份域主键
     * @param userId      用户主键
     * @param rawPassword 新口令明文
     * @throws BusinessException 口令不符合域策略或与历史口令重复
     */
    private void apply(Long realmId, Long userId, String rawPassword) {
        // 1. 取域口令策略(不存在则按保守基线创建)
        PasswordPolicy policy = securityPolicyManager.ensurePasswordPolicy(realmId);
        // 2. 复杂度 + 历史重复校验
        passwordPolicyService.validateAll(userId, rawPassword, policy);
        // 3. 停用旧凭证、写入新凭证、令牌版本递增(既有登录态全部失效)
        userManager.resetPassword(userId, rawPassword);
        // 4. 记录口令历史(编码后存储,仅用于重复性校验)
        int keep = policy.getHistoryCount() == null ? 0 : policy.getHistoryCount();
        passwordHistoryManager.record(realmId, userId, passwordEncoder.encode(rawPassword), keep);
        // 5. 凭证变更提醒:事务提交后异步投递,不拖慢改密接口
        securityAlertService.alertCredentialChanged(realmId, userId, "登录口令已变更",
                "你的登录口令刚刚被修改,此前登录的设备需要使用新口令重新登录。");
    }
}
