package club.beenest.iam.authz.authentication;

import club.beenest.iam.authz.authentication.step.AuthenticationContext;
import club.beenest.iam.authz.authentication.step.AuthenticationStep;
import club.beenest.iam.authz.authentication.step.AuthenticationStepChain;
import club.beenest.iam.authz.authentication.step.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证步骤链的编排行为。
 * <p>链路是登录主干的骨架,编排错了会让校验被跳过或被重复执行:
 * 顺序错 = 先查状态再验口令(账号枚举),跳过错 = 验证码门禁失效。
 * 因此这里断言的是"顺序"与"跳过语义"本身,而不是某个步骤的业务判断。</p>
 */
class AuthenticationStepChainTest {

    /**
     * 记录执行轨迹的步骤。
     */
    private static final class RecordingStep implements AuthenticationStep {

        private final String name;
        private final Stage stage;
        private final int order;
        private final List<String> trace;
        private final boolean applies;
        private final boolean failing;

        RecordingStep(String name, Stage stage, int order, List<String> trace,
                      boolean applies, boolean failing) {
            this.name = name;
            this.stage = stage;
            this.order = order;
            this.trace = trace;
            this.applies = applies;
            this.failing = failing;
        }

        @Override
        public Stage stage() {
            return stage;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public boolean appliesTo(AuthenticationContext context) {
            return applies;
        }

        @Override
        public void execute(AuthenticationContext context) throws AuthenticationException {
            trace.add(name);
            if (failing) {
                throw new BadCredentialsException("步骤失败:" + name);
            }
        }
    }

    private static AuthenticationContext context() {
        return new AuthenticationContext("alice", "secret", null);
    }

    @Test
    @DisplayName("按阶段顺序执行,与注册顺序无关:乱序传入也按 PRE_CHECK → FIRST_FACTOR → POST_CHECK → SECOND_FACTOR 走")
    void executesInStageOrderRegardlessOfRegistrationOrder() {
        List<String> trace = new ArrayList<>();
        AuthenticationStepChain chain = new AuthenticationStepChain(List.of(
                new RecordingStep("second", Stage.SECOND_FACTOR, 10, trace, true, false),
                new RecordingStep("post", Stage.POST_CHECK, 10, trace, true, false),
                new RecordingStep("first", Stage.FIRST_FACTOR, 10, trace, true, false),
                new RecordingStep("pre", Stage.PRE_CHECK, 10, trace, true, false)));

        chain.execute(context());

        assertEquals(List.of("pre", "first", "post", "second"), trace,
                "阶段顺序必须编译期固定,不能被 bean 注册顺序影响");
    }

    @Test
    @DisplayName("同阶段内按 order 升序:order 决定先后,是唯一的阶段内排序依据")
    void executesInOrderWithinSameStage() {
        List<String> trace = new ArrayList<>();
        AuthenticationStepChain chain = new AuthenticationStepChain(List.of(
                new RecordingStep("captcha", Stage.PRE_CHECK, 30, trace, true, false),
                new RecordingStep("realm", Stage.PRE_CHECK, 10, trace, true, false),
                new RecordingStep("lock", Stage.PRE_CHECK, 20, trace, true, false)));

        chain.execute(context());

        assertEquals(List.of("realm", "lock", "captcha"), trace);
    }

    @Test
    @DisplayName("同阶段同 order 时按步骤名排序:保证不同 JVM 与启动顺序下行为一致")
    void breaksTiesByName() {
        List<String> trace = new ArrayList<>();
        AuthenticationStepChain chain = new AuthenticationStepChain(List.of(
                new RecordingStep("bravo", Stage.PRE_CHECK, 10, trace, true, false),
                new RecordingStep("alpha", Stage.PRE_CHECK, 10, trace, true, false)));

        chain.execute(context());

        assertEquals(List.of("alpha", "bravo"), trace);
    }

    @Test
    @DisplayName("appliesTo 返回 false 时跳过且不视为失败:后续步骤继续执行")
    void skipsStepsThatDoNotApply() {
        List<String> trace = new ArrayList<>();
        AuthenticationStepChain chain = new AuthenticationStepChain(List.of(
                new RecordingStep("always", Stage.PRE_CHECK, 10, trace, true, false),
                new RecordingStep("skipped", Stage.PRE_CHECK, 20, trace, false, false),
                new RecordingStep("after", Stage.PRE_CHECK, 30, trace, true, false)));

        chain.execute(context());

        assertEquals(List.of("always", "after"), trace, "被跳过的步骤不应出现在轨迹里");
    }

    @Test
    @DisplayName("任一步骤抛异常即终止整条链,后续步骤不再执行")
    void stopsAtFirstFailure() {
        List<String> trace = new ArrayList<>();
        AuthenticationStepChain chain = new AuthenticationStepChain(List.of(
                new RecordingStep("first", Stage.PRE_CHECK, 10, trace, true, false),
                new RecordingStep("boom", Stage.PRE_CHECK, 20, trace, true, true),
                new RecordingStep("never", Stage.PRE_CHECK, 30, trace, true, false)));

        assertThrows(BadCredentialsException.class, () -> chain.execute(context()));
        assertEquals(List.of("first", "boom"), trace, "失败之后的步骤绝不能执行");
    }

    @Test
    @DisplayName("空步骤集合是空操作:链本身不抛异常,由调用方判断上下文是否完整")
    void emptyChainIsNoOp() {
        AuthenticationStepChain chain = new AuthenticationStepChain(List.of());

        chain.execute(context());

        assertTrue(chain.getSteps().isEmpty());
    }

    @Test
    @DisplayName("null 步骤集合按空处理,不抛空指针")
    void nullStepsTreatedAsEmpty() {
        AuthenticationStepChain chain = new AuthenticationStepChain(null);

        assertTrue(chain.getSteps().isEmpty());
    }

    @Test
    @DisplayName("getSteps 返回不可变列表,防止调用方改坏已排序的执行顺序")
    void stepsAreImmutable() {
        AuthenticationStepChain chain = new AuthenticationStepChain(List.of(
                new RecordingStep("only", Stage.PRE_CHECK, 10, new ArrayList<>(), true, false)));

        assertThrows(UnsupportedOperationException.class,
                () -> chain.getSteps().add(new RecordingStep("x", Stage.PRE_CHECK, 20,
                        new ArrayList<>(), true, false)));
    }
}
