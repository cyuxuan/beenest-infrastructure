package club.beenest.payment.security;

/**
 * 应用上下文 — 请求级 app_id 传播
 *
 * <p>由 {@link club.beenest.payment.security.InternalApiFilter} 在认证成功后设置，
 * 下游业务代码可通过 {@link #getAppId()} 获取当前请求对应的业务系统标识。</p>
 *
 * <p>必须在请求结束时通过 {@link #clear()} 清理，防止线程池中 ThreadLocal 泄漏。
 * 清理由 {@link club.beenest.payment.security.AppContextFilter} 在 finally 块中执行。</p>
 *
 * @author System
 * @since 2026-07-16
 */
public final class AppContext {

    private AppContext() {
    }

    private static final ThreadLocal<String> APP_ID = new ThreadLocal<>();

    /**
     * 设置当前请求的 app_id
     *
     * @param appId 业务系统标识
     */
    public static void setAppId(String appId) {
        APP_ID.set(appId);
    }

    /**
     * 获取当前请求的 app_id
     *
     * @return 业务系统标识，未设置时返回 null
     */
    public static String getAppId() {
        return APP_ID.get();
    }

    /**
     * 清理当前线程的 app_id，防止 ThreadLocal 泄漏
     */
    public static void clear() {
        APP_ID.remove();
    }
}
