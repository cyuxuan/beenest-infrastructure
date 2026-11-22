package club.beenest.iam.web.controller;

import club.beenest.iam.web.config.IamThemeProperties;
import club.beenest.iam.web.config.IamThemeSelector;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 主题静态资源控制器({@code /theme-static/**})。
 * <p>主题需按请求的身份域选择,无法用启动期固定的 resource handler 映射;
 * 解析顺序与模板一致:当前域主题目录 → 全局主题目录。
 * 域主题缺资源(如漏拷图片)时回落全局主题。</p>
 * <p>资源只从 {@code classpath:/themes/{theme}/static/} 读取,路径含 ".." 一律拒绝,
 * 不读取 classpath 其它位置。</p>
 */
@Controller
public class ThemeStaticController {

    private static final String PREFIX = "/theme-static/";

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("css", "text/css;charset=UTF-8"),
            Map.entry("js", "application/javascript;charset=UTF-8"),
            Map.entry("mjs", "application/javascript;charset=UTF-8"),
            Map.entry("html", "text/html;charset=UTF-8"),
            Map.entry("json", "application/json;charset=UTF-8"),
            Map.entry("map", "application/json;charset=UTF-8"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("webp", "image/webp"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("eot", "application/vnd.ms-fontobject"),
            Map.entry("txt", "text/plain;charset=UTF-8"));

    private final IamThemeSelector themeSelector;
    private final IamThemeProperties themeProperties;

    public ThemeStaticController(IamThemeSelector themeSelector, IamThemeProperties themeProperties) {
        this.themeSelector = themeSelector;
        this.themeProperties = themeProperties;
    }

    /**
     * 提供主题静态资源。
     *
     * @param request 当前请求(按 URI 提取主题内相对路径)
     * @return 资源响应(带按扩展名推断的 Content-Type);路径非法或未命中时 404
     */
    @GetMapping("/theme-static/**")
    public ResponseEntity<Resource> serve(HttpServletRequest request) {
        String path = extractPath(request);
        if (path.isEmpty() || path.contains("..")) {
            return ResponseEntity.notFound().build();
        }
        for (String theme : themeCandidates()) {
            Resource resource = new ClassPathResource("themes/" + theme + "/static/" + path);
            if (resource.isReadable()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType(path)))
                        .body(resource);
            }
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 主题候选顺序:当前域主题优先,全局主题兜底;两者相同时只保留一个。
     *
     * @return 主题目录名列表
     */
    private List<String> themeCandidates() {
        String current = themeSelector.currentTheme();
        String global = themeProperties.getActive();
        if (current.equals(global)) {
            return List.of(current);
        }
        return List.of(current, global);
    }

    /**
     * 从请求 URI 提取主题内相对路径并解码。
     *
     * @param request 当前请求
     * @return 相对路径;前缀不匹配或百分号编码非法时返回空串
     */
    private String extractPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String prefix = request.getContextPath() + PREFIX;
        if (!uri.startsWith(prefix)) {
            return "";
        }
        String raw = uri.substring(prefix.length());
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 非法百分号编码:按不存在处理
            return "";
        }
    }

    /**
     * 按扩展名给内容类型,未知类型按二进制流下发。
     *
     * @param path 资源相对路径
     * @return Content-Type 值
     */
    private String contentType(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot >= 0 ? path.substring(dot + 1).toLowerCase() : "";
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }
}
