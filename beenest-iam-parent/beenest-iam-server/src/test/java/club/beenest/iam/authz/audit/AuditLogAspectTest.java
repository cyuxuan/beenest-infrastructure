package club.beenest.iam.authz.audit;

import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.event.AuditLogEvent;
import club.beenest.iam.common.result.Result;
import org.aspectj.lang.JoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计目标主键解析回归测试。
 * <p>应用作用域接口形如 {@code /apps/{appId}/workspace/menus/{menuId}}:
 * 审计目标必须是被操作资源的主键,而不是作用域里的应用主键,
 * 否则事后无法从审计日志定位被改动的资源。</p>
 */
class AuditLogAspectTest {

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final AuditLogAspect aspect = new AuditLogAspect(publisher);

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("响应体携带主键时以响应主键为准")
    void prefersPayloadId() {
        bindPathVariables(Map.of("appId", "2", "menuId", "4"));

        aspect.afterReturning(joinPoint(new Object[]{2L, 4L}), auditAnnotation(),
                Result.success(new MenuPayload(9L, "expense.approve")));

        assertEquals(9L, capturedEvent().targetId());
    }

    @Test
    @DisplayName("应用作用域路径取资源主键而非应用主键")
    void usesScopedResourceId() {
        bindPathVariables(Map.of("appId", "2", "menuId", "4"));

        aspect.afterReturning(joinPoint(new Object[]{2L, 4L}), auditAnnotation(), Result.success());

        assertEquals(4L, capturedEvent().targetId());
    }

    @Test
    @DisplayName("平台级路径取 id 变量")
    void usesPlainIdVariable() {
        bindPathVariables(Map.of("id", "7"));

        aspect.afterReturning(joinPoint(new Object[]{7L}), auditAnnotation(), Result.success());

        assertEquals(7L, capturedEvent().targetId());
    }

    @Test
    @DisplayName("没有路径变量时回退到首个 Long 参数")
    void fallsBackToFirstLongArgument() {
        aspect.afterReturning(joinPoint(new Object[]{3L}), auditAnnotation(), Result.success());

        assertEquals(3L, capturedEvent().targetId());
    }

    private void bindPathVariables(Map<String, String> variables) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private JoinPoint joinPoint(Object[] args) {
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(args);
        return joinPoint;
    }

    private AuditLogEvent capturedEvent() {
        ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
        verify(publisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    private LogAudit auditAnnotation() {
        try {
            Method method = AuditedTarget.class.getDeclaredMethod("update", Long.class, Long.class);
            return method.getAnnotation(LogAudit.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 审计目标方法:注解本身即测试对象。 */
    private static final class AuditedTarget {
        @LogAudit(action = "MENU_UPDATE", description = "修改菜单")
        public void update(Long appId, Long menuId) {
        }
    }

    /** 模拟响应体携带资源主键的记录。 */
    private record MenuPayload(Long id, String menuCode) {
    }
}
