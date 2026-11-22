package club.beenest.iam.authz.tenant;

/**
 * 身份域上下文持有器(线程级)。
 * <p>由 {@code RealmContextFilter} 在请求入口设置、请求结束后清理;
 * 业务代码经 {@link #get()} 读取当前身份域,无上下文(如全局请求)返回 null。</p>
 */
public final class RealmContextHolder {

    private static final ThreadLocal<RealmContext> HOLDER = new ThreadLocal<>();

    private RealmContextHolder() {
    }

    /**
     * 设置当前身份域上下文。
     *
     * @param context 身份域上下文
     */
    public static void set(RealmContext context) {
        HOLDER.set(context);
    }

    /**
     * 读取当前身份域上下文。
     *
     * @return 身份域上下文;未设置(如全局请求)返回 null
     */
    public static RealmContext get() {
        return HOLDER.get();
    }

    /** 清理上下文,请求结束时必须调用,防止线程复用串域。 */
    public static void clear() {
        HOLDER.remove();
    }
}
