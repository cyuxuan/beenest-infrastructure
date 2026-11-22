package club.beenest.iam.authz.authentication.step;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;

import java.util.Comparator;
import java.util.List;

/**
 * 认证步骤链。
 * <p>持有全部 {@link AuthenticationStep},按"阶段序号 → 步骤序号 → 步骤名"排序后顺序执行。
 * 排序在构造时完成一次,执行期只做遍历,不做动态编排。</p>
 * <p>某一步抛出 {@link AuthenticationException} 即终止整条链,异常原样上抛给调用方;
 * 链路本身不捕获、不转换异常,失败语义完全由步骤决定。</p>
 */
public class AuthenticationStepChain {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationStepChain.class);

    /** 已排序的步骤,构造后不再变更 */
    private final List<AuthenticationStep> steps;

    /**
     * 创建步骤链。
     * <p>排序键依次为阶段序号、步骤序号、步骤名;步骤名为最后一级键,保证同一位置上的
     * 多个步骤在不同 JVM 与不同启动顺序下执行顺序一致,避免偶发的行为差异。</p>
     *
     * @param steps 全部步骤,可为空集合(此时链为空操作)
     */
    public AuthenticationStepChain(List<AuthenticationStep> steps) {
        this.steps = steps == null ? List.of() : steps.stream()
                .sorted(Comparator.comparingInt((AuthenticationStep step) -> step.stage().order())
                        .thenComparingInt(AuthenticationStep::order)
                        .thenComparing(AuthenticationStep::name))
                .toList();
        if (log.isDebugEnabled()) {
            log.debug("认证步骤链已装配: {}", this.steps.stream()
                    .map(step -> step.stage() + "/" + step.name())
                    .toList());
        }
    }

    /**
     * 依次执行全部步骤。
     *
     * @param context 认证上下文,由调用方填充登录名与凭据
     * @throws AuthenticationException 任一步骤校验不通过时抛出,链路立即终止
     */
    public void execute(AuthenticationContext context) throws AuthenticationException {
        for (AuthenticationStep step : steps) {
            if (!step.appliesTo(context)) {
                log.debug("跳过认证步骤: {}/{}", step.stage(), step.name());
                continue;
            }
            step.execute(context);
        }
    }

    /**
     * 已排序的步骤列表。
     *
     * @return 不可变列表,顺序即执行顺序
     */
    public List<AuthenticationStep> getSteps() {
        return steps;
    }
}
