package club.beenest.iam.authz.admission;

import java.util.Set;

/**
 * 准入判定结果。
 * <p>三个分量共同表达四种状态,需联合判别:放行 {@code allowed=true};补第一因素
 * {@code allowed=false, requiresMfa=false} 且 allowedLoginTypes 非空;仅补二步验证
 * {@code allowed=false, requiresMfa=true} 且 allowedLoginTypes 为空;直接拒绝
 * {@code allowed=false, requiresMfa=false} 且 allowedLoginTypes 为空。</p>
 *
 * @param allowed           是否放行
 * @param allowedLoginTypes 被要求的第一因素存储码集合;为空时表示不限定第一因素
 *                          (仅要求二步验证)或直接拒绝,需结合 {@code requiresMfa} 与 {@code allowed} 判别
 * @param requiresMfa       是否要求二步验证
 */
public record AdmissionDecision(boolean allowed, Set<String> allowedLoginTypes, boolean requiresMfa) {

    /**
     * 构造放行结果。
     *
     * @return allowed 为 true 的判定
     */
    public static AdmissionDecision allow() {
        return new AdmissionDecision(true, Set.of(), false);
    }

    /**
     * 构造补第一因素结果。
     *
     * @param allowedLoginTypes 被要求的第一因素存储码集合
     * @return allowed 为 false、requiresMfa 为 false 且携带方式集合的判定
     */
    public static AdmissionDecision requireFactor(Set<String> allowedLoginTypes) {
        return new AdmissionDecision(false, allowedLoginTypes, false);
    }

    /**
     * 构造补二步验证结果。
     *
     * @return allowed 为 false、requiresMfa 为 true 且方式集合为空的判定
     */
    public static AdmissionDecision requireMfa() {
        return new AdmissionDecision(false, Set.of(), true);
    }

    /**
     * 构造直接拒绝结果:无法通过补验解决。
     * <p>用于客户端策略处于配置损坏态(白名单存了值却一个都解析不出来)。与
     * {@link #requireFactor} 靠不允许、不要求 MFA、白名单为空三者同时成立区分。</p>
     *
     * @return allowed 为 false、requiresMfa 为 false 且方式集合为空的判定
     */
    public static AdmissionDecision deny() {
        return new AdmissionDecision(false, Set.of(), false);
    }
}
