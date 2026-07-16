package club.beenest.payment.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 Mapper 方法跳过多租户 appId 自动注入
 *
 * <p>标注此注解的 Mapper 方法不会自动追加 {@code AND app_id = ?} 条件，
 * 也不会自动为参数对象的 {@code appId} 字段赋值。</p>
 *
 * <p>适用场景：</p>
 * <ul>
 *   <li>管理员接口 — 需要跨租户查看所有数据</li>
 *   <li>内部调度器 — 定时任务无 HTTP 请求上下文，AppContext 为 null</li>
 *   <li>MQ 消费者 — 消息处理无请求级 appId</li>
 *   <li>数据修复接口 — 需要操作指定租户的数据</li>
 * </ul>
 *
 * @see TenantAppIdInterceptor
 * @see AppContext
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantIgnore {
}
