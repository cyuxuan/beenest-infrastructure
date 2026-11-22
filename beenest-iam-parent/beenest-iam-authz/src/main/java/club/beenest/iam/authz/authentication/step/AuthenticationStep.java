package club.beenest.iam.authz.authentication.step;

import org.springframework.security.core.AuthenticationException;

/**
 * 认证步骤。
 * <p>登录主干由若干步骤按 {@link Stage} 顺序串联而成,每个步骤只负责一件事:
 * 读取 {@link AuthenticationContext} 中已有状态,校验或补充状态,失败则抛
 * {@link AuthenticationException} 终止链路。</p>
 * <p>扩展方式:实现本接口并注册为 Spring bean,即被 {@link AuthenticationStepChain}
 * 自动纳入链路。新增第一因素时把步骤声明在 {@link Stage#FIRST_FACTOR},
 * 前置检查声明在 {@link Stage#PRE_CHECK},无需改动 Provider 或配置。</p>
 */
public interface AuthenticationStep {

    /**
     * 本步骤所属阶段。
     *
     * @return 阶段枚举,决定步骤在链路中的大位置
     */
    Stage stage();

    /**
     * 步骤标识,用于日志与链路追踪。
     *
     * @return 简短、稳定、小写连字符风格的标识,如 {@code credential}
     */
    String name();

    /**
     * 同阶段内的执行顺序。
     *
     * @return 数值越小越先执行;默认 100,内置步骤使用 10 的整数倍以便插入
     */
    default int order() {
        return 100;
    }

    /**
     * 本步骤是否参与本次认证。
     * <p>返回 false 时链路跳过该步骤,不视为失败。用于"按条件生效"的步骤,
     * 例如仅当域策略启用某项检查时才执行。</p>
     *
     * @param context 当前认证上下文
     * @return true 表示执行;false 表示跳过
     */
    default boolean appliesTo(AuthenticationContext context) {
        return true;
    }

    /**
     * 执行本步骤。
     * <p>实现应通过 {@link AuthenticationContext} 的 setter 写入本步骤产出的状态,
     * 供后续步骤读取。</p>
     *
     * @param context 当前认证上下文,已由链路填充登录名、口令与请求
     * @throws AuthenticationException 校验不通过时抛出,链路立即终止
     */
    void execute(AuthenticationContext context) throws AuthenticationException;
}
