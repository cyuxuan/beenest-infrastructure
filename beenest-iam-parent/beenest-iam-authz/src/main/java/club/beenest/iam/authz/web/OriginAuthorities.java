package club.beenest.iam.authz.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 来源地址与本站 authority 的比对工具。
 * <p>CORS 判定与来源校验过滤器必须用同一套口径,否则会出现"过滤器放行、CORS 拒绝"
 * 这类自相矛盾的结果。只比对 host:port,不比对协议:反向代理后面容器看到的协议
 * 常常是 http,而浏览器发出的是 https,按协议比对会把自家页面判成跨源。</p>
 * <p>不透明来源({@code Origin: null})在这里一律判为不可信:它无法与任何 authority 比对,
 * 也不该被"猜"成可信——沙箱化 iframe 拿到的正是这个值。本站页面本不该发出它,
 * 出现时应当去查响应头 {@code Referrer-Policy} 是否把 referrer 清空了,
 * 浏览器据此推导出的 Origin 就会退化成不透明来源。</p>
 */
public final class OriginAuthorities {

    /** 不透明来源的字面量:浏览器无法给出真实来源时发送它 */
    public static final String OPAQUE_ORIGIN = "null";

    /** 代理链路传递的原始 Host 头 */
    private static final String HEADER_FORWARDED_HOST = "X-Forwarded-Host";

    /**
     * 来源可信度。
     * <p>{@code UNKNOWN} 与 {@code UNTRUSTED} 必须区分:前者是"看不出来源"
     * (非浏览器客户端、服务端调用),后者是"明确来自别处"。前者按非浏览器客户端放行,
     * 后者才需要拒绝——否则 curl 与系统间调用会被一并挡死。</p>
     */
    public enum Verdict {
        /** 与本站同主机,或命中已登记的前端来源 */
        TRUSTED,
        /** 明确来自其它站点,或来源不可验证(不透明来源) */
        UNTRUSTED,
        /** 无法判定:没有任何可用于比对的来源信息 */
        UNKNOWN
    }

    private OriginAuthorities() {
    }

    /**
     * 判定请求来源的可信度。
     * <p>真实 {@code Origin} 直接比对主机名;不透明 {@code Origin} 判为不可信;
     * 无法解析的 {@code Origin} 按缺失处理,继续看 {@code Referer}。</p>
     *
     * @param request 当前请求
     * @return 来源可信度,永不为 null;无任何来源信息时返回 {@link Verdict#UNKNOWN}
     */
    public static Verdict verdictOf(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !origin.isBlank()) {
            if (isOpaque(origin)) {
                return Verdict.UNTRUSTED;
            }
            if (authorityOf(origin) != null) {
                return isSameSite(request, origin) ? Verdict.TRUSTED : Verdict.UNTRUSTED;
            }
            // 无法解析的 Origin 值按缺失处理,继续看 Referer
        }
        return verdictFromReferer(request);
    }

    /**
     * 判断来源是否为不透明来源。
     *
     * @param origin Origin 头原始值,可为 null
     * @return true 表示是字面量 {@code null}(大小写不敏感、忽略首尾空白)
     */
    public static boolean isOpaque(String origin) {
        return origin != null && OPAQUE_ORIGIN.equalsIgnoreCase(origin.trim());
    }

    /**
     * 按 {@code Referer} 判定来源可信度。
     * <p>只作为 {@code Origin} 缺失时的兜底:非浏览器客户端通常两个头都不带,
     * 取不到时返回 {@link Verdict#UNKNOWN} 而不是拒绝。</p>
     *
     * @param request 当前请求
     * @return 来源可信度;Referer 缺失或无法解析时返回 {@link Verdict#UNKNOWN}
     */
    private static Verdict verdictFromReferer(HttpServletRequest request) {
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null || referer.isBlank()) {
            return Verdict.UNKNOWN;
        }
        return isSameSite(request, referer) ? Verdict.TRUSTED : Verdict.UNTRUSTED;
    }

    /**
     * 由 URL 提取 host:port。
     *
     * @param url 绝对地址(Origin 或 Referer 的值),可为 null
     * @return 归一化后的 host:port;不是合法绝对地址时返回 null
     */
    public static String authorityOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            return normalize(uri.getPort() < 0 ? host : host + ":" + uri.getPort());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 判断来源是否属于本站。
     * <p>候选地址按可信度取三个:Host 头、{@code X-Forwarded-Host}(代理改写 Host 时仍有原始值)、
     * 以及已被转发头策略修正过的 {@code getServerName()/getServerPort()}。</p>
     * <p><b>只比对主机名,不比对端口</b>:端口经代理转发后与浏览器看到的往往不同
     * (典型:浏览器访问 80,容器监听 8080),按端口严格比对会把自家页面的请求判成跨源,
     * 表现为登录报 {@code Invalid CORS request}。浏览器把不同端口视为不同源,但服务端
     * 判断"是不是自家页面"时主机名才是稳定信号;真正需要拦的是完全不同的域名。</p>
     *
     * @param request 当前请求
     * @param origin  请求携带的 Origin 值,可为 null
     * @return true 表示来源与本站同主机;来源无法解析或主机名不匹配时返回 false
     */
    public static boolean isSameSite(HttpServletRequest request, String origin) {
        return isSameHost(request, authorityOf(origin));
    }

    /**
     * 判断给定 authority 的主机名是否与本站一致。
     * <p>供来源校验过滤器直接复用:那里拿到的是已归一化的 authority 字符串。</p>
     *
     * @param request   当前请求
     * @param authority 归一化后的 host[:port],可为 null
     * @return true 表示主机名与本站候选之一相同;无法解析或不匹配时返回 false
     */
    public static boolean isSameHost(HttpServletRequest request, String authority) {
        String host = hostOf(authority);
        if (host == null) {
            return false;
        }
        for (String candidate : hostAuthorities(request)) {
            if (host.equals(hostOf(candidate))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取 authority 中的主机名部分。
     *
     * @param authority 归一化后的 host[:port],可为 null
     * @return 主机名;输入为空时返回 null。IPv6 保留方括号形式
     */
    public static String hostOf(String authority) {
        if (authority == null || authority.isBlank()) {
            return null;
        }
        String value = normalize(authority);
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(0, end + 1) : value;
        }
        int colon = value.lastIndexOf(':');
        return colon > 0 ? value.substring(0, colon) : value;
    }

    /**
     * 取本站的 host:port。
     * <p>用于只比对单一权威地址的场景(如来源校验过滤器的日志与判定)。</p>
     *
     * @param request 当前请求
     * @return 归一化后的 host:port;Host 头缺失时回落到服务端名与端口
     */
    public static String hostAuthority(HttpServletRequest request) {
        String host = request.getHeader(HttpHeaders.HOST);
        if (host == null || host.isBlank()) {
            return normalize(request.getServerName() + ":" + request.getServerPort());
        }
        return normalize(host);
    }

    /**
     * 收集本站可能的 authority 候选。
     *
     * @param request 当前请求
     * @return 归一化并去重后的候选列表,至少含一项
     */
    public static List<String> hostAuthorities(HttpServletRequest request) {
        List<String> candidates = new ArrayList<>(3);
        addCandidate(candidates, request.getHeader(HttpHeaders.HOST));
        addCandidate(candidates, firstValue(request.getHeader(HEADER_FORWARDED_HOST)));
        addCandidate(candidates, request.getServerName() + ":" + request.getServerPort());
        return candidates;
    }

    /**
     * 归一化 host:port:小写并去掉协议的默认端口。
     *
     * @param authority 原始 host[:port]
     * @return 归一化结果
     */
    public static String normalize(String authority) {
        String value = authority.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(":443") || value.endsWith(":80")) {
            return value.substring(0, value.lastIndexOf(':'));
        }
        return value;
    }

    /**
     * 取逗号分隔头部的第一个值。
     *
     * @param header 头部原始值,可为 null
     * @return 第一个非空片段;无有效值时返回 null
     */
    private static String firstValue(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        for (String part : header.split(",")) {
            if (!part.isBlank()) {
                return part;
            }
        }
        return null;
    }

    /**
     * 追加一个候选地址(去空、归一化、去重)。
     *
     * @param candidates 候选列表
     * @param authority  原始 host[:port],可为 null
     */
    private static void addCandidate(List<String> candidates, String authority) {
        if (authority == null || authority.isBlank()) {
            return;
        }
        String normalized = normalize(authority);
        if (!candidates.contains(normalized)) {
            candidates.add(normalized);
        }
    }
}
