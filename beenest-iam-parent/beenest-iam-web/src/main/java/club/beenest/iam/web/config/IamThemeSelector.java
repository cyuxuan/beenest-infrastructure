package club.beenest.iam.web.config;

import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.common.theme.ThemeRegistry;
import org.springframework.stereotype.Component;

/**
 * 当前请求的主题选择器。
 * <p>解析顺序:身份域主题({@code iam_realm.theme},经 {@link ThemeRegistry} 校验存在)
 * → 全局主题({@code iam.theme.active})。域主题配置了不存在的目录名时不报错,
 * 静默回落全局主题,登录页仍可打开。</p>
 * <p>视图名由 {@link #view(String)} 统一加主题前缀:解析器前缀为
 * {@code classpath:/themes/},视图名 {@code {theme}/login/login}。</p>
 */
@Component
public class IamThemeSelector {

    private final IamThemeProperties themeProperties;
    private final ThemeRegistry themeRegistry;

    public IamThemeSelector(IamThemeProperties themeProperties, ThemeRegistry themeRegistry) {
        this.themeProperties = themeProperties;
        this.themeRegistry = themeRegistry;
    }

    /**
     * 解析当前请求应使用的主题目录名。
     *
     * @return 域主题存在时返回域主题名;域主题缺失或未注册时返回全局主题
     */
    public String currentTheme() {
        var context = RealmContextHolder.get();
        if (context != null && context.realm() != null) {
            String realmTheme = context.realm().theme();
            if (realmTheme != null && !realmTheme.isBlank() && themeRegistry.exists(realmTheme)) {
                return realmTheme;
            }
        }
        return themeProperties.getActive();
    }

    /**
     * 给主题页视图名加主题前缀。
     *
     * @param pageView 页面视图名,如 {@code login/login}
     * @return 带主题段的视图名,如 {@code nestid/login/login}
     */
    public String view(String pageView) {
        return currentTheme() + "/" + pageView;
    }
}
