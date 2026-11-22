package club.beenest.iam.authz.tenant;

import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 身份域上下文过滤器。
 * <p>置于每条过滤器链最前:从 /realms/{realmKey} 路径或 realm 请求参数解析身份域,
 * 写入线程上下文供下游使用;域不存在或未启用时不设置上下文,由后续端点按 404 处理。</p>
 */
public class RealmContextFilter extends OncePerRequestFilter {

    /** 协议端点路径:/realms/{realmKey}/oauth2/authorize 等 */
    private static final Pattern REALM_PATH = Pattern.compile("^/realms/([^/]+)(/.*)?$");

    private final RealmProvider realmProvider;

    public RealmContextFilter(RealmProvider realmProvider) {
        this.realmProvider = realmProvider;
    }

    /**
     * 解析请求所属身份域并写入线程上下文,请求结束后清理。
     * <p>优先按 /realms/{realmKey} 路径解析,其次按 realm 参数;域不存在或未启用时
     * 不设置上下文,由后续端点按 404 处理。</p>
     *
     * @param request     当前请求
     * @param response    当前响应
     * @param filterChain 后续过滤器链
     * @throws ServletException 过滤器链执行异常
     * @throws IOException      IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // 优先按路径解析(协议端点),其次按 realm 参数解析(全局登录页/登录提交)
            String realmKey = resolveRealmKey(request);
            if (realmKey != null) {
                RealmModel realm = realmProvider.findByRealmKey(realmKey).orElse(null);
                if (realm != null && realm.enabled()) {
                    RealmContextHolder.set(new RealmContext(realm.id(), realm.realmKey(), realm));
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            // 请求结束必须清理,防止线程复用时串域
            RealmContextHolder.clear();
        }
    }

    /**
     * 解析请求所属身份域标识。
     * <p>按 servletPath(上下文内路径)匹配而非 requestURI:部署在子路径时
     * requestURI 带上下文前缀,正则匹配不到。</p>
     *
     * @param request 当前请求
     * @return 身份域标识;路径与 realm 参数均未命中时返回 null
     */
    private static String resolveRealmKey(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
        }
        Matcher matcher = REALM_PATH.matcher(path);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        String realmParam = request.getParameter("realm");
        return (realmParam == null || realmParam.isBlank()) ? null : realmParam;
    }
}
