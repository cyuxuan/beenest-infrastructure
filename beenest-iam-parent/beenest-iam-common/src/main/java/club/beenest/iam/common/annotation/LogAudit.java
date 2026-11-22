package club.beenest.iam.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解。
 * <p>标注在管理端写操作方法上,由审计切面发布 {@code AuditLogEvent},
 * 监听方在调用线程内追加写入 {@code iam_audit_log};写入失败不中断业务操作。</p>
 * <p>{@link #description()} 支持 {@code {路径变量}} 占位(如 {@code {appCode}}),
 * 落库前替换为实际取值。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LogAudit {

    /**
     * 操作类型,写入审计日志的 action 字段。
     *
     * @return 操作类型编码,如 USER_CREATE / ROLE_ASSIGN
     */
    String action();

    /**
     * 操作描述,支持 `{路径变量}` 占位。
     *
     * @return 中文描述,落库前已替换占位为实际取值;默认为空
     */
    String description() default "";
}
