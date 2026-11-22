package club.beenest.iam.authz.web;

import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * 来源校验过滤器:状态变更请求必须来自本站或已登记的前端来源。
 * <p>登录类接口按设计豁免 CSRF 令牌(首屏调用时用户还没有会话,拿不到令牌),于是
 * "把受害者登录成攻击者账号"这类登录 CSRF 只能靠来源判定拦住。CSRF 令牌与来源校验
 * 覆盖的失败模式不同:前者防的是浏览器自动附带凭据,后者防的是"凭据由用户显式提交
 * 但提交给了攻击者构造的请求"。</p>
 * <p>判据:非安全方法且请求带 {@code Origin}(或 {@code Referer})时,其 host 必须是本站
 * 或配置的前端来源;两者都不带的请求按非浏览器客户端放行(curl、服务端调用本来就没有来源头)。
 * 比对只取 host:port,不比对协议——反向代理后面容器看到的协议常常是 http。</p>
 * <p>判定收敛到 {@link OriginAuthorities#verdictOf}:不透明来源({@code Origin: null})
 * 一律不可信——本站页面本不该发出这个值,出现时说明响应头把 referrer 清空了。</p>
 */
public class IamOriginGuardFilter extends OncePerRequestFilter {

    /** 不改变状态的请求方法,无需来源校验 */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final Set<String> allowedOrigins;
    private final ObjectMapper objectMapper;

    /**
     * 构造过滤器。
     *
     * @param allowedOrigins 允许的跨源前端来源(形如 {@code https://admin.example.com}),可为空集合;
     *                       无法解析的条目会被忽略
     * @param objectMapper   JSON 映射器,用于写出 403 响应体
     */
    public IamOriginGuardFilter(java.util.Collection<String> allowedOrigins, ObjectMapper objectMapper) {
        this.allowedOrigins = allowedOrigins == null
                ? Set.of()
                : allowedOrigins.stream()
                        .map(OriginAuthorities::authorityOf)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.objectMapper = objectMapper;
    }

    /**
     * 校验来源后放行。
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
        if (SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            filterChain.doFilter(request, response);
            return;
        }
        if (isTrusted(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        ResultJsonWriter.write(response, objectMapper, HttpStatus.FORBIDDEN.value(),
                Result.fail(ErrorCode.FORBIDDEN));
    }

    /**
     * 取请求来源。
     *
     * @param request 当前请求
     * @return 规范化后的来源(host:port);无 Origin 时回落到 Referer 的 host,两者都缺失返回 null
     */
    private static String resolveSource(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !origin.isBlank()) {
            String authority = OriginAuthorities.authorityOf(origin);
            if (authority != null) {
                return authority;
            }
        }
        String referer = request.getHeader(HttpHeaders.REFERER);
        return referer == null || referer.isBlank() ? null : OriginAuthorities.authorityOf(referer);
    }

    /**
     * 判断请求是否可信。
     * <p>与 CORS 判定共用 {@link OriginAuthorities#verdictOf}:两层口径不一致会出现
     * "来源校验拒绝、CORS 放行"这类自相矛盾,排障时看到的现象与日志对不上。</p>
     * <p>只有明确判定为跨站时才继续查前端来源白名单;无法判定来源的请求按非浏览器客户端放行。</p>
     *
     * @param request 当前请求
     * @return true 表示来源可信、无法判定来源,或命中已登记的前端来源
     */
    private boolean isTrusted(HttpServletRequest request) {
        if (OriginAuthorities.verdictOf(request) != OriginAuthorities.Verdict.UNTRUSTED) {
            return true;
        }
        String source = resolveSource(request);
        return source != null && allowedOrigins.contains(source);
    }
}
