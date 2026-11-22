package club.beenest.iam.authz.admission;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 客户端准入策略。
 * <p>描述客户端接受哪些第一因素与是否强制二步验证,不决定认证方式本身;认证方式由
 * 域级决定,准入策略在域级之上做收窄。</p>
 * <p>换 IdP 不牵动各客户端白名单,改单客户端策略不影响全域。</p>
 *
 * @param allowedMethods 允许的第一因素集合,空集合表示不限制,沿用域级可用方式
 * @param requireMfa     是否强制二步验证
 * @param denyAll        配置损坏态,一律拒绝
 */
public record ClientAdmissionPolicy(Set<LoginMethod> allowedMethods, boolean requireMfa, boolean denyAll) {

    /** 构造时冻结集合,调用方事后改动不影响判定结果;denyAll 下白名单归一为空。 */
    public ClientAdmissionPolicy {
        allowedMethods = allowedMethods == null || allowedMethods.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(allowedMethods));
        if (denyAll) {
            allowedMethods = Set.of();
        }
    }

    /**
     * 两参构造,不表达配置损坏态。
     *
     * @param allowedMethods 允许的第一因素集合
     * @param requireMfa     是否强制二步验证
     */
    public ClientAdmissionPolicy(Set<LoginMethod> allowedMethods, boolean requireMfa) {
        this(allowedMethods, requireMfa, false);
    }

    /**
     * 不限制任何方式、也不强制 MFA 的策略,存量客户端默认。
     *
     * @return 空白名单、requireMfa 与 denyAll 均为 false 的策略
     */
    public static ClientAdmissionPolicy unrestricted() {
        return new ClientAdmissionPolicy(Set.of(), false, false);
    }

    /**
     * 配置损坏态策略:一律拒绝。
     * <p>存了白名单却解析不出任何方式时使用;降级为 unrestricted 等于把生效的限制
     * 静默解除。</p>
     * <p>方法名带 Policy 后缀:record 分量 {@code denyAll} 占用同名同参访问器,
     * Java 不允许静态方法与实例方法同签名并存。</p>
     *
     * @return denyAll 为 true 的策略
     */
    public static ClientAdmissionPolicy denyAllPolicy() {
        return new ClientAdmissionPolicy(Set.of(), false, true);
    }

    /**
     * 判断会话的认证方式是否被本策略接受。
     * <p>三条规则按顺序判别:denyAll 为真一律拒绝,配置损坏态优先级最高,白名单
     * 解析不出方式时不落回不限制;白名单为空全部接受;白名单非空只接受集合内方式,
     * 登录方式未知按不满足处理,无法证明满足要求时要求重新证明。</p>
     *
     * @param loginType 会话的认证方式存储码
     * @return true 表示被接受
     */
    public boolean admits(String loginType) {
        if (denyAll) {
            return false;
        }
        if (allowedMethods.isEmpty()) {
            return true;
        }
        LoginMethod method = LoginMethod.fromCode(loginType);
        return method != null && allowedMethods.contains(method);
    }

    /**
     * 白名单的存储码集合,供管理端回显与前端选项比对。
     * <p>返回码而非枚举:控制台表单与数据库存的都是码。</p>
     *
     * @return 白名单方式对应的存储码集合
     */
    public Set<String> allowedMethodCodes() {
        Set<String> codes = new LinkedHashSet<>();
        allowedMethods.forEach(method -> codes.add(method.code()));
        return Collections.unmodifiableSet(codes);
    }

    /**
     * 是否强制二步验证。
     *
     * @return requireMfa 字段值
     */
    public boolean requiresMfa() {
        return requireMfa;
    }
}
