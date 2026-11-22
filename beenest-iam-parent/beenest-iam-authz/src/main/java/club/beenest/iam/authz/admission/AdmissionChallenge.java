package club.beenest.iam.authz.admission;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一次待处理的准入补验挑战。
 * <p>表示当前会话的认证方式不满足目标客户端要求,需以被接受的方式再证明一次;
 * 记录归属会话、目标客户端、可接受方式、是否要求二步验证与一次性令牌。</p>
 *
 * @param sid               会话标识,挑战归属的会话
 * @param clientId          目标客户端
 * @param allowedLoginTypes 被接受的第一因素存储码集合
 * @param requireMfa        目标客户端是否还要求二步验证
 * @param returnTo          补验成功后的回跳地址,本域授权端点路径
 * @param challengeToken    一次性令牌,挑战页表单回传用于防 CSRF
 * @param createdAt         签发时刻,毫秒时间戳
 */
public record AdmissionChallenge(
        String sid,
        String clientId,
        Set<String> allowedLoginTypes,
        boolean requireMfa,
        String returnTo,
        String challengeToken,
        long createdAt
) {
    /** 构造时冻结集合,允许入参为 null 时归一为空集。 */
    public AdmissionChallenge {
        allowedLoginTypes = allowedLoginTypes == null
                ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(allowedLoginTypes));
    }

    /**
     * 判断本次认证方式是否被挑战接受。
     * <p>空集合表示不限制,任何方式都接受;空集合仅来自仅要求二步验证的挑战,
     * 配置损坏的 denyAll 策略在签发入口已被拒绝,不会走到这里。</p>
     *
     * @param loginType 本次认证方式存储码
     * @return true 表示被接受
     */
    public boolean allows(String loginType) {
        if (allowedLoginTypes.isEmpty()) {
            return true;
        }
        return loginType != null && allowedLoginTypes.contains(loginType);
    }
}
