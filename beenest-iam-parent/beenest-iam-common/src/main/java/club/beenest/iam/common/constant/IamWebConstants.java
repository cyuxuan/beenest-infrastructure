package club.beenest.iam.common.constant;

/**
 * Web 层共享常量。
 * <p>存放 web 与 authz 模块共用的请求参数名与存储命名空间。</p>
 */
public final class IamWebConstants {

    /** 私有构造:工具类不可实例化 */
    private IamWebConstants() {
    }

    /**
     * 登录失败消息的一次性票据参数名(URL 参数)。
     * <p>消息正文存于 Redis,URL 中只携带票据。</p>
     */
    public static final String LOGIN_ERROR_TICKET_PARAM = "err";

    /** 登录失败消息的业务命名空间(票据的存储隔离位)。 */
    public static final String LOGIN_ERROR_NAMESPACE = "loginerror";
}
