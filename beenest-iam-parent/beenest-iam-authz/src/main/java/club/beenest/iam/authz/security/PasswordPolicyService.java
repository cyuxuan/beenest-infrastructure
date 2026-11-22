package club.beenest.iam.authz.security;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.persistence.entity.PasswordPolicy;
import club.beenest.iam.persistence.manager.IamPasswordHistoryManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 口令策略服务。
 * <p>集中执行复杂度校验与历史重复校验,供管理端建号/重置与用户自助改密共用。</p>
 */
@Service
public class PasswordPolicyService {

    /** 保守基线:域未配置策略时使用 */
    public static final PasswordPolicy FALLBACK_POLICY = defaultPolicy();

    private final IamPasswordHistoryManager passwordHistoryManager;
    private final PasswordEncoder passwordEncoder;

    public PasswordPolicyService(IamPasswordHistoryManager passwordHistoryManager,
                                 PasswordEncoder passwordEncoder) {
        this.passwordHistoryManager = passwordHistoryManager;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 保守基线策略(与 DDL 默认值一致)。
     *
     * @return 默认口令策略
     */
    public static PasswordPolicy defaultPolicy() {
        PasswordPolicy policy = new PasswordPolicy();
        policy.setMinLength(8);
        policy.setMaxLength(64);
        policy.setRequireUppercase(false);
        policy.setRequireLowercase(false);
        policy.setRequireDigit(true);
        policy.setRequireSymbol(false);
        policy.setHistoryCount(0);
        policy.setExpireDays(0);
        return policy;
    }

    /**
     * 校验新口令是否满足域策略(复杂度)。
     *
     * @param rawPassword 新口令明文
     * @param policy      域口令策略;为空时使用保守基线
     * @throws BusinessException 不满足时抛出,消息说明具体原因
     */
    public void validate(String rawPassword, PasswordPolicy policy) {
        PasswordPolicy effective = policy == null ? FALLBACK_POLICY : policy;
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "口令不能为空");
        }
        List<String> violations = new ArrayList<>();
        if (rawPassword.length() < effective.getMinLength()) {
            violations.add("长度不足(至少 " + effective.getMinLength() + " 位)");
        }
        if (rawPassword.length() > effective.getMaxLength()) {
            violations.add("长度超出(至多 " + effective.getMaxLength() + " 位)");
        }
        if (Boolean.TRUE.equals(effective.getRequireUppercase())
                && !rawPassword.chars().anyMatch(Character::isUpperCase)) {
            violations.add("缺少大写字母");
        }
        if (Boolean.TRUE.equals(effective.getRequireLowercase())
                && !rawPassword.chars().anyMatch(Character::isLowerCase)) {
            violations.add("缺少小写字母");
        }
        if (Boolean.TRUE.equals(effective.getRequireDigit())
                && !rawPassword.chars().anyMatch(Character::isDigit)) {
            violations.add("缺少数字");
        }
        if (Boolean.TRUE.equals(effective.getRequireSymbol())
                && rawPassword.chars().noneMatch(c -> !Character.isLetterOrDigit(c))) {
            violations.add("缺少特殊符号");
        }
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "口令不符合安全策略:" + String.join(";", violations));
        }
    }

    /**
     * 校验新口令是否与最近 N 个历史口令重复。
     *
     * @param userId      用户主键
     * @param rawPassword 新口令明文
     * @param policy      域口令策略;为空时使用保守基线
     * @throws BusinessException 与历史口令重复
     */
    public void validateHistory(Long userId, String rawPassword, PasswordPolicy policy) {
        PasswordPolicy effective = policy == null ? FALLBACK_POLICY : policy;
        int historyCount = effective.getHistoryCount() == null ? 0 : effective.getHistoryCount();
        if (historyCount <= 0) {
            return;
        }
        for (var history : passwordHistoryManager.listRecent(userId, historyCount)) {
            if (passwordEncoder.matches(rawPassword, history.getPasswordHash())) {
                throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION,
                        "新口令不能与最近 " + historyCount + " 个已使用口令相同");
            }
        }
    }

    /**
     * 组合校验:复杂度 + 历史。
     *
     * @param userId      用户主键
     * @param rawPassword 新口令明文
     * @param policy      域口令策略;为空时使用保守基线
     * @throws BusinessException 不满足复杂度要求或与历史口令重复
     */
    public void validateAll(Long userId, String rawPassword, PasswordPolicy policy) {
        validate(rawPassword, policy);
        validateHistory(userId, rawPassword, policy);
    }
}
