package club.beenest.payment.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 应用上下文清理过滤器
 *
 * <p>在请求处理完成后清理 {@link AppContext} 中的 ThreadLocal，
 * 防止 Servlet 线程池中的 ThreadLocal 泄漏。</p>
 *
 * <p>必须在 {@link InternalApiFilter} 之后执行，
 * 通过 {@code @Order} 确保优先级低于 InternalApiFilter（数值更大）。</p>
 *
 * @author System
 * @since 2026-07-16
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class AppContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 确保每次请求结束后清理 ThreadLocal，防止线程复用导致的数据污染
            AppContext.clear();
        }
    }
}
