package club.beenest.iam.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 链路标识过滤器。
 * <p>为每个请求生成链路标识,写入 MDC 与响应头 {@value #TRACE_ID_HEADER},
 * 请求结束后清理。标识仅用于日志关联,不含业务语义,不参与鉴权。</p>
 */
public class IamTraceIdFilter extends OncePerRequestFilter {

    /** 响应头名:调用方可凭它向运维报出这次请求 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    /** MDC 键名 */
    public static final String TRACE_ID_MDC = "traceId";
    /** 链路标识随机字节数 */
    private static final int TRACE_ID_BYTES = 12;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 生成链路标识,写入 MDC 与响应头,并在请求结束后清理。
     *
     * @param request     当前请求
     * @param response    当前响应
     * @param filterChain 过滤器链
     * @throws ServletException 下游过滤器抛出
     * @throws IOException      下游过滤器 IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = newTraceId();
        MDC.put(TRACE_ID_MDC, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理:线程会被容器复用,残留的标识会让下一次请求的日志串到别的请求上
            MDC.remove(TRACE_ID_MDC);
        }
    }

    /**
     * 生成链路标识。
     *
     * @return Base64URL 编码的随机标识
     */
    private String newTraceId() {
        byte[] bytes = new byte[TRACE_ID_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
