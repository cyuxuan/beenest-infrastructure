package club.beenest.iam.authz.audit;

import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.event.AuditLogEvent;
import club.beenest.iam.common.result.Result;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 操作审计切面。
 * <p>拦截 {@code @LogAudit} 标注的方法,成功返回后发布审计事件;方法抛异常时不记录,
 * 避免未生效的操作误导排查。</p>
 */
@Aspect
@Component
public class AuditLogAspect {

    /**
     * 作用域路径变量:表示操作所处的范围,不代表被操作对象本身。
     * <p>应用作用域接口形如 {@code /apps/{appId}/workspace/menus/{menuId}},审计目标
     * 应取 menuId 而非 appId,保留被改动资源的可定位性。</p>
     */
    private static final Set<String> SCOPE_VARIABLES = Set.of("appId", "realmId", "clientId");

    private final ApplicationEventPublisher eventPublisher;

    public AuditLogAspect(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 方法成功返回后发布审计事件。
     *
     * @param joinPoint   连接点,用于提取目标对象主键
     * @param logAudit    注解上的操作类型与描述
     * @param returnValue 方法返回值,可作为主键来源之一
     */
    @AfterReturning(pointcut = "@annotation(logAudit)", returning = "returnValue")
    public void afterReturning(JoinPoint joinPoint, LogAudit logAudit, Object returnValue) {
        // 定时任务等无认证上下文的场景按系统操作记录
        IamUserPrincipal principal = CurrentUserHolder.optional();
        eventPublisher.publishEvent(new AuditLogEvent(
                logAudit.action(),
                resolveDescription(logAudit.description()),
                principal == null ? null : principal.getUserId(),
                principal == null ? null : principal.getUsername(),
                principal == null ? null : principal.getRealmKey(),
                resolveTargetType(logAudit.action()),
                resolveTargetId(joinPoint, returnValue),
                currentIp(),
                LocalDateTime.now()));
    }

    /**
     * 取当前请求来源 IP。
     *
     * @return 请求远端地址;非 HTTP 线程(定时任务等)返回 null
     */
    private static String currentIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getRemoteAddr();
        }
        return null;
    }

    /**
     * 用当前路径变量替换描述模板中的 {name} 占位。
     * <p>注解上的描述是编译期常量,允许写 {@code {appCode}} 之类的占位;运行时替换后
     * 审计记录里就带上了具体对象,便于事后定位。</p>
     *
     * @param template 描述模板
     * @return 替换后的描述;模板为空或不含 { 时原样返回
     */
    private static String resolveDescription(String template) {
        if (template == null || template.isBlank() || !template.contains("{")) {
            return template;
        }
        Map<String, String> variables = pathVariables();
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /**
     * 取操作类型首段作为目标类型,USER_CREATE 得到 USER。
     *
     * @param action 操作类型
     * @return 目标类型;action 为空返回 null
     */
    private static String resolveTargetType(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        int index = action.indexOf('_');
        return index > 0 ? action.substring(0, index) : action;
    }

    /**
     * 按可信度依次解析审计目标主键。
     * <ol>
     *   <li>响应体携带的资源主键:新建场景路径里没有主键,由响应给出;</li>
     *   <li>路径变量中名为 {@code id} 的变量:平台级资源,如 /users/{id};</li>
     *   <li>路径变量中最后一个非作用域的 {@code *Id}:应用作用域资源,如 menuId、roleId;</li>
     *   <li>方法参数中的首个 Long:非 HTTP 调用的兜底。</li>
     * </ol>
     *
     * @param joinPoint   连接点,提供方法参数
     * @param returnValue 方法返回值
     * @return 主键;四种来源均无匹配时返回 null
     */
    private static Long resolveTargetId(JoinPoint joinPoint, Object returnValue) {
        Long fromPayload = extractId(returnValue);
        if (fromPayload != null) {
            return fromPayload;
        }
        Map<String, String> variables = pathVariables();
        Long byId = toLong(variables.get("id"));
        if (byId != null) {
            return byId;
        }
        Long scoped = null;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (entry.getKey().endsWith("Id") && !SCOPE_VARIABLES.contains(entry.getKey())) {
                Long value = toLong(entry.getValue());
                if (value != null) {
                    // 路径越深的主键越贴近被操作对象,取最后一个
                    scoped = value;
                }
            }
        }
        return scoped != null ? scoped : firstLongArgument(joinPoint);
    }

    /**
     * 从响应体中提取资源主键,Result 会先解出 data 再反射读取。
     *
     * @param returnValue 方法返回值
     * @return 主键;payload 为空或无 id/getId 访问器时返回 null
     */
    private static Long extractId(Object returnValue) {
        Object payload = returnValue instanceof Result<?> result ? result.getData() : returnValue;
        if (payload == null) {
            return null;
        }
        Long value = invokeIdAccessor(payload, "id");
        return value != null ? value : invokeIdAccessor(payload, "getId");
    }

    /**
     * 反射调用 id 访问器:record 用 {@code id()},实体用 {@code getId()}。
     *
     * @param payload  响应体 payload
     * @param accessor 访问器方法名
     * @return 主键;访问器不存在、抛异常或返回值非 Number 时返回 null
     */
    private static Long invokeIdAccessor(Object payload, String accessor) {
        try {
            Method method = payload.getClass().getMethod(accessor);
            Object value = method.invoke(payload);
            return value instanceof Number number ? number.longValue() : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 取当前请求的路径变量,顺序与路径模板一致。
     *
     * @return 路径变量映射;非 HTTP 线程或未挂模板变量属性时返回空 Map
     */
    private static Map<String, String> pathVariables() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return Map.of();
        }
        Object variables = servletAttributes.getRequest().getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), String.valueOf(value)));
        return result;
    }

    /**
     * 取方法参数中首个 Long 值,作为无路径变量时的兜底。
     *
     * @param joinPoint 连接点
     * @return 首个 Long 参数;参数数组为空或无 Long 时返回 null
     */
    private static Long firstLongArgument(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Long value) {
                return value;
            }
        }
        return null;
    }

    /**
     * 解析字符串主键。
     *
     * @param value 待解析字符串
     * @return 解析后的 Long;空白或非数字返回 null
     */
    private static Long toLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
