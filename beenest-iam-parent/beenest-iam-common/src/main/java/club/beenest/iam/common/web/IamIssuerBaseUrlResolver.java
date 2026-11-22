package club.beenest.iam.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 外部访问基址解析器。
 * <p>用于拼接协议端点与令牌签发方地址。默认按当前请求推导,
 * 可通过 {@code iam.issuer-base-url} 覆盖为对外域名。</p>
 */
@Component
public class IamIssuerBaseUrlResolver {

    private static final Logger log = LoggerFactory.getLogger(IamIssuerBaseUrlResolver.class);

    private final String configuredBaseUrl;

    public IamIssuerBaseUrlResolver(@Value("${iam.issuer-base-url:}") String configuredBaseUrl) {
        this.configuredBaseUrl = configuredBaseUrl;
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            // 未配置时签发方与校验基址都取自当前请求,不同主机名(或代理未透传协议)访问
            // 同一张令牌时会判为签发方不一致,直接表现为 401
            log.warn("iam.issuer-base-url 未配置,签发方按请求推导;"
                    + "经反向代理或多主机名访问时请显式配置对外地址");
        }
    }

    /**
     * 解析外部访问基址,形如 {@code https://iam.example.com}。
     *
     * @param request 当前请求;配置为空时据此推导
     * @return 不含结尾斜杠的基址
     */
    public String resolve(HttpServletRequest request) {
        // 1. 配置优先:反向代理场景下请求头可能是内网地址
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            return stripTrailingSlash(configuredBaseUrl.trim());
        }
        // 2. 配置为空时按当前请求推导(含端口)
        return ServletUriComponentsBuilder.fromRequest(request)
                .replacePath(null)
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    /**
     * 按当前线程绑定的请求解析外部访问基址。
     * <p>供不在方法签名上持有请求的组件(如令牌解码器)使用。</p>
     *
     * @return 不含结尾斜杠的基址;未配置且当前线程无请求上下文时返回 null
     */
    public String resolveCurrent() {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            return stripTrailingSlash(configuredBaseUrl.trim());
        }
        HttpServletRequest request = currentRequest();
        return request == null ? null : resolve(request);
    }

    /**
     * 取当前线程绑定的请求。
     *
     * @return 当前请求;非 Web 线程返回 null
     */
    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servletAttributes
                ? servletAttributes.getRequest() : null;
    }

    /**
     * 去掉结尾斜杠,避免与后续路径拼接出双斜杠。
     *
     * @param url 基址
     * @return 去掉结尾斜杠后的基址
     */
    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
