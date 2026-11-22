package club.beenest.iam.web.controller;

import club.beenest.iam.authz.authorization.ClientApplicationAccessFilter;
import club.beenest.iam.web.config.IamThemeSelector;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 错误页控制器。
 * <p>渲染主题化错误页,向用户隐藏内部异常细节,仅展示状态码与面向用户的说明。
 * 标题、说明与处理建议按状态码区分,文案取自文案源并按请求语言解析。</p>
 * <p>应用授权闸门拒绝时会在请求上留下应用名称,错误页据此把"哪个应用"写进说明。</p>
 */
@Controller
public class ErrorPageController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ErrorPageController.class);

    private final IamThemeSelector themeSelector;
    private final MessageSource messageSource;

    public ErrorPageController(IamThemeSelector themeSelector, MessageSource messageSource) {
        this.themeSelector = themeSelector;
        this.messageSource = messageSource;
    }

    /**
     * 渲染错误页(读取请求属性中的状态码)。
     * <p>没有错误上下文的直接访问(常见于错误页上的语言切换链接)改为回到入口页:
     * 这种请求本来就没有出错,渲染成 500 只会让用户以为服务坏了。</p>
     *
     * @param request 当前请求,从中读取 {@link RequestDispatcher#ERROR_STATUS_CODE} 与拒绝应用名称
     * @param model   视图模型,写入状态码、英文眉标、标题、说明与处理建议
     * @return 主题化错误页视图名;直接访问时重定向到入口页
     */
    @GetMapping("/error")
    public String errorPage(HttpServletRequest request, Model model) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (!(status instanceof Integer code)) {
            return "redirect:/";
        }
        String prefix = "page.error." + code;
        String appName = deniedAppName(request);
        model.addAttribute("status", code);
        model.addAttribute("kicker", kicker(code));
        model.addAttribute("heading", resolve(prefix + ".heading", "page.error.heading"));
        model.addAttribute("message", message(code, appName));
        model.addAttribute("hintTitle", resolve(prefix + ".hintTitle", "page.error.hintTitle"));
        model.addAttribute("hint", resolve(prefix + ".hint", "page.error.hint"));
        model.addAttribute("appName", appName);
        logRejection(request, code);
        return themeSelector.view("error/error");
    }

    /**
     * 把被拒请求的技术细节写进日志。
     * <p>页面对用户只给可行动的文案(如"回调地址与登记值不一致"),真正的判定原因
     * (OAuth2 的 {@code invalid_request} / {@code invalid_redirect_uri} 等)只进日志:
     * 运维据此定位,终端用户不必读懂协议栈术语。</p>
     * <p>授权端点的 400 一律带查询串:受众、回调地址这些关键参数都在其中,
     * 没有它就只能靠猜是哪一项不匹配。</p>
     *
     * @param request 当前请求
     * @param code    HTTP 状态码
     */
    private static void logRejection(HttpServletRequest request, int code) {
        if (code < 400) {
            return;
        }
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        Object detail = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        if (detail == null) {
            Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            if (exception instanceof Throwable throwable) {
                detail = throwable.getMessage();
            }
        }
        // 带上调用方:被拒请求常来自探测、健康检查或遗留地址,没有来源无法定位
        String caller = caller(request);
        if (code == HttpStatus.BAD_REQUEST.value()) {
            log.warn("请求被拒绝: status={} uri={} query={} caller={} detail={}",
                    code, uri, request.getQueryString(), caller, detail);
            return;
        }
        log.warn("请求被拒绝: status={} uri={} caller={} detail={}", code, uri, caller, detail);
    }

    /**
     * 提取调用方标识。
     *
     * <p>反向代理后 {@code getRemoteAddr()} 是代理地址,优先取 {@code X-Forwarded-For} 首段;
     * UA 由调用方任意填写且可能很长,截断到 120 字符。</p>
     *
     * @param request 当前请求
     * @return 形如 {@code ip ua="..."} 的调用方描述
     */
    private static String caller(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        String agent = request.getHeader("User-Agent");
        if (agent == null || agent.isBlank()) {
            return ip;
        }
        return ip + " ua=\"" + (agent.length() > 120 ? agent.substring(0, 120) + "..." : agent) + "\"";
    }

    /**
     * 按状态码给出面向用户的说明。
     *
     * @param code    HTTP 状态码
     * @param appName 被拒绝访问的应用名称,非应用授权拒绝时为 {@code null}
     * @return 当前请求语言下的说明文案;403 且带应用名称时说明中会点名该应用
     */
    private String message(int code, String appName) {
        if (code == HttpStatus.FORBIDDEN.value() && appName != null && !appName.isBlank()) {
            // MessageFormat 把单引号当转义符,应用名里的单引号需要成对写入才能原样输出
            String quoted = appName.replace("'", "''");
            return messageSource.getMessage("page.error.403.appMessage", new Object[]{quoted},
                    "page.error.403.appMessage", LocaleContextHolder.getLocale());
        }
        return resolve("page.error." + code, "page.error.fallback");
    }

    /**
     * 取应用授权闸门写入的应用名称。
     *
     * @param request 当前请求
     * @return 应用名称;不是应用授权拒绝时返回 {@code null}
     */
    private String deniedAppName(HttpServletRequest request) {
        Object value = request.getAttribute(ClientApplicationAccessFilter.DENIED_APP_NAME_ATTRIBUTE);
        return value instanceof String name && !name.isBlank() ? name : null;
    }

    /**
     * 页面上方的英文眉标,与主题里其它页面的命名风格保持一致。
     *
     * @param code HTTP 状态码
     * @return 眉标文案
     */
    private String kicker(int code) {
        return switch (code) {
            case 400 -> "REQUEST REJECTED";
            case 401 -> "SESSION EXPIRED";
            case 403 -> "ACCESS DENIED";
            case 404 -> "PAGE NOT FOUND";
            case 500 -> "SERVER ERROR";
            default -> "REQUEST FAILED";
        };
    }

    /**
     * 解析文案键,键缺失时回退到备用键。
     *
     * @param key         首选文案键
     * @param fallbackKey 备用文案键
     * @return 当前请求语言下的文案;备用键也缺失时返回首选键本身
     */
    private String resolve(String key, String fallbackKey) {
        return messageSource.getMessage(key, null,
                messageSource.getMessage(fallbackKey, null, fallbackKey, LocaleContextHolder.getLocale()),
                LocaleContextHolder.getLocale());
    }
}
