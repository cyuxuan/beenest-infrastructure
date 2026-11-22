package club.beenest.iam.authz.session;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Duration;

/**
 * 浏览器会话 Cookie。
 * <p>会话标识由平台签发并经此 Cookie 携带,快照在共享 Redis,任意实例都可加载;
 * 容器会话 id 只在创建它的进程内有效,多实例部署时会被反复登出,故不采用。</p>
 * <p>Cookie 属性:</p>
 * <ul>
 *   <li><b>HttpOnly</b>:脚本读不到会话标识,缓解 XSS 窃取;</li>
 *   <li><b>SameSite=Lax</b>:跨站跳转(应用门户点进目标应用)仍携带 Cookie,
 *       保持单点登录能力;同时挡住大部分 CSRF 场景(写操作另有 CSRF 令牌兜底);</li>
 *   <li><b>Secure</b>:生产强制走 HTTPS。</li>
 * </ul>
 */
public final class IamSessionCookie {

    /** 会话 Cookie 名 */
    public static final String COOKIE_NAME = "IAM_SESSION";

    /** SameSite 取值:跨站跳转仍携带,跨站子请求不携带 */
    private static final String SAME_SITE = "SameSite";
    private static final String SAME_SITE_LAX = "Lax";

    private IamSessionCookie() {
    }

    /**
     * 从请求中读取会话标识。
     *
     * @param request 当前请求
     * @return 会话标识;Cookie 缺失或值为空白时返回 null
     */
    public static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value;
            }
        }
        return null;
    }

    /**
     * 写出会话 Cookie。
     *
     * @param response 响应
     * @param sid      会话标识
     * @param secure   是否只走 HTTPS(生产为 true)
     * @param maxAge   Cookie 有效期(秒)
     */
    public static void write(HttpServletResponse response, String sid, boolean secure, Duration maxAge) {
        Cookie cookie = new Cookie(COOKIE_NAME, sid);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setSecure(secure);
        // SameSite 必须显式设置:容器会话的 same-site 配置只作用于 JSESSIONID,
        // 不会套到自签 Cookie 上;浏览器默认值在老客户端与嵌入式 WebView 中不可靠
        cookie.setAttribute(SAME_SITE, SAME_SITE_LAX);
        cookie.setMaxAge((int) Math.min(maxAge.toSeconds(), Integer.MAX_VALUE));
        response.addCookie(cookie);
    }

    /**
     * 清除会话 Cookie(注销时调用)。
     * <p>刻意不带 Secure:按 RFC 6265bis,带 Secure 的 Set-Cookie 经非安全信道下发
     * 会被客户端整条忽略,开发档(HTTP)将无法清除会话。Cookie 按 name+domain+path
     * 匹配,不带 Secure 同样能删除带 Secure 写入的那条。</p>
     *
     * @param response 写出清除指令的响应
     */
    public static void clear(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setAttribute(SAME_SITE, SAME_SITE_LAX);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }
}
