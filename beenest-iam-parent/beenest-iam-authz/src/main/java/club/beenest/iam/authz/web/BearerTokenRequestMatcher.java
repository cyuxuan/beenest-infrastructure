package club.beenest.iam.authz.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 携带有效 Bearer 令牌的请求匹配器。
 * <p>用于豁免 CSRF 校验:显式携带 Authorization 头且令牌验签通过的请求不依赖浏览器自动附带
 * 的 Cookie,天然免疫 CSRF。</p>
 * <p>判据必须是"令牌有效"而不是"请求头存在"。只看前缀时,任何页面都能给跨站请求加一个
 * 伪造的 {@code Authorization: Bearer x} 从而跳过 CSRF 校验,而认证环节仍回落到 Cookie,
 * 等于把 CSRF 防护整个关掉。</p>
 * <p>此处只做签名与时效校验,不查令牌状态:CSRF 判定发生在认证之前,此时还不需要
 * "账号是否被禁用""令牌是否被吊销"这类业务结论,认证环节会再完整校验一次。</p>
 */
public class BearerTokenRequestMatcher implements RequestMatcher {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;

    /**
     * 构造匹配器。
     *
     * @param jwtDecoder 仅校验签名与时效的解码器
     */
    public BearerTokenRequestMatcher(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    /**
     * 判断请求是否携带可验签的 Bearer 令牌。
     *
     * @param request 当前请求
     * @return true 表示令牌存在且验签通过
     */
    @Override
    public boolean matches(HttpServletRequest request) {
        String token = resolveToken(request);
        if (token == null) {
            return false;
        }
        try {
            jwtDecoder.decode(token);
            return true;
        } catch (RuntimeException e) {
            // 验签失败、格式非法、签发方未知都按"没有令牌"处理,继续要求 CSRF 令牌
            return false;
        }
    }

    /**
     * 从请求头提取 Bearer 令牌。
     *
     * @param request 当前请求
     * @return 令牌原文;头缺失、前缀不符或值为空时返回 null
     */
    private static String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
