package club.beenest.iam.web.config;

import club.beenest.iam.common.i18n.IamLocaleResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Locale;

/**
 * 页面公共模型装配。
 * <p>给所有主题页统一注入 {@code themeName}(当前主题目录名,供页面引用主题片段)、
 * {@code langSwitch}(语言切换链接的目标语言、目标地址)与 {@code fmt}(时间与文本格式化)。</p>
 * <p>作用范围限于协议页控制器所在包;REST 接口不使用这些模型。</p>
 */
@ControllerAdvice(basePackages = "club.beenest.iam.web.controller")
public class IamPageModelAdvice {

    private final IamThemeSelector themeSelector;
    private final IamTemplateFormatter templateFormatter;

    public IamPageModelAdvice(IamThemeSelector themeSelector, IamTemplateFormatter templateFormatter) {
        this.themeSelector = themeSelector;
        this.templateFormatter = templateFormatter;
    }

    /**
     * 当前请求的主题目录名,供页面拼接片段路径。
     *
     * @return 主题目录名,如 {@code nestid}
     */
    @ModelAttribute("themeName")
    public String themeName() {
        return themeSelector.currentTheme();
    }

    /**
     * 语言切换信息。
     *
     * @param request 当前请求,用于保留原有查询参数
     * @return 当前语言标签、目标语言标签、目标语言自称与切换后的完整地址
     */
    @ModelAttribute("langSwitch")
    public LangSwitch langSwitch(HttpServletRequest request) {
        Locale current = LocaleContextHolder.getLocale();
        boolean chinese = Locale.SIMPLIFIED_CHINESE.getLanguage().equals(current.getLanguage());
        String targetTag = chinese ? "en" : "zh-CN";
        // 链接文案显示目标语言的自称:中文界面显示 English,英文界面显示中文
        String label = chinese ? "English" : "中文";
        return new LangSwitch(IamLocaleResolver.toTag(current), targetTag, label, switchUrl(request, targetTag));
    }

    /**
     * 模板格式化工具。
     *
     * @return 页面可通过 {@code ${fmt.dateTime(...)}} 调用
     */
    @ModelAttribute("fmt")
    public IamTemplateFormatter formatter() {
        return templateFormatter;
    }

    /**
     * 构造切换语言后的地址:沿用当前路径与查询参数,只替换 {@code lang}。
     *
     * @param request   当前请求
     * @param targetTag 目标语言标签
     * @return 带 {@code lang} 参数的相对地址;原查询串中的 {@code lang} 会被剔除,避免重复
     */
    private String switchUrl(HttpServletRequest request, String targetTag) {
        StringBuilder url = new StringBuilder(request.getRequestURI());
        boolean first = true;
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            for (String pair : query.split("&")) {
                if (pair.isBlank() || pair.startsWith("lang=")) {
                    continue;
                }
                url.append(first ? '?' : '&').append(pair);
                first = false;
            }
        }
        url.append(first ? '?' : '&').append("lang=").append(targetTag);
        return url.toString();
    }

    /**
     * 语言切换所需的页面数据。
     *
     * @param currentTag 当前语言标签
     * @param targetTag  目标语言标签
     * @param label      目标语言的自称,直接作为链接文案
     * @param url        切换后的地址
     */
    public record LangSwitch(String currentTag, String targetTag, String label, String url) {
    }
}
