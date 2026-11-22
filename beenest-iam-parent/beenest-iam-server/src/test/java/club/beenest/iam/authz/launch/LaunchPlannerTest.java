package club.beenest.iam.authz.launch;

import club.beenest.iam.persistence.entity.ApplicationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动规划器单元测试。
 * <p>判定只有一张表,这里逐条钉住:哪种配置算可启动、跳到哪里、不可启动的原因是什么。</p>
 */
class LaunchPlannerTest {

    private final LaunchPlanner planner = new LaunchPlanner();

    @Test
    @DisplayName("应用不存在:不可启动")
    void nullAppIsBlocked() {
        LaunchPlan plan = planner.plan(null);
        assertTrue(plan.blocked());
        assertNull(plan.target());
        assertEquals("应用不存在", plan.hint());
    }

    @Test
    @DisplayName("入口地址就是跳转目标")
    void entryUrlIsTarget() {
        LaunchPlan plan = planner.plan(app("oa", "https://oa.example.com"));
        assertFalse(plan.blocked());
        assertEquals("oa", plan.appCode());
        assertEquals("https://oa.example.com", plan.target());
        assertNull(plan.hint(), "可启动时没有原因可讲");
    }

    @Test
    @DisplayName("入口地址带空白:去空白后使用")
    void entryUrlIsTrimmed() {
        assertEquals("https://oa.example.com", planner.plan(app("oa", "  https://oa.example.com  ")).target());
    }

    @Test
    @DisplayName("未配置入口地址:不可启动")
    void missingEntryUrlIsBlocked() {
        LaunchPlan plan = planner.plan(app("oa", null));
        assertTrue(plan.blocked());
        assertNull(plan.target());
        assertEquals("应用未配置入口地址", plan.hint());
    }

    @Test
    @DisplayName("入口地址为空白串:按未配置处理")
    void blankEntryUrlIsBlocked() {
        assertTrue(planner.plan(app("oa", "   ")).blocked());
    }

    /** 构造一个应用。 */
    private static ApplicationService app(String appCode, String entryUrl) {
        ApplicationService app = new ApplicationService();
        app.setAppCode(appCode);
        app.setEntryUrl(entryUrl);
        app.setEnabled(true);
        return app;
    }
}
