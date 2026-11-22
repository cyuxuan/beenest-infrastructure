package club.beenest.iam.web.config;

import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.common.theme.ThemeRegistry;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.SubjectMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 主题选择器测试:域主题优先、回落全局、view 前缀拼接。
 * <p>覆盖配置错误的兜底:域主题配了不存在的目录名,登录页仍能用全局主题打开。</p>
 */
class IamThemeSelectorTest {

    private final IamThemeProperties properties = new IamThemeProperties();
    private final ThemeRegistry registry = new ThemeRegistry();
    private final IamThemeSelector selector = new IamThemeSelector(properties, registry);

    @AfterEach
    void tearDown() {
        RealmContextHolder.clear();
    }

    @Test
    void fallsBackToGlobalWhenNoRealmContext() {
        properties.setActive("nestid");
        assertEquals("nestid", selector.currentTheme());
        assertEquals("nestid/login/login", selector.view("login/login"));
    }

    @Test
    void usesRealmThemeWhenDirectoryExists() {
        properties.setActive("nestid");
        RealmContextHolder.set(contextWithTheme("nestid"));
        assertEquals("nestid", selector.currentTheme());
    }

    @Test
    void fallsBackToGlobalWhenRealmThemeMissing() {
        properties.setActive("nestid");
        RealmContextHolder.set(contextWithTheme("no-such-theme"));
        assertEquals("nestid", selector.currentTheme());
    }

    @Test
    void fallsBackToGlobalWhenRealmThemeBlank() {
        properties.setActive("nestid");
        RealmContextHolder.set(contextWithTheme("  "));
        assertEquals("nestid", selector.currentTheme());
    }

    @Test
    void fallsBackToGlobalWhenRealmThemeNull() {
        properties.setActive("nestid");
        RealmContextHolder.set(contextWithTheme(null));
        assertEquals("nestid", selector.currentTheme());
    }

    private static RealmContext contextWithTheme(String theme) {
        RealmModel realm = new RealmModel(1L, "default", "默认域", true, true,
                300L, 1800L, 1800L, 36000L, SubjectMode.LOGIN_NAME, theme);
        return new RealmContext(1L, "default", realm);
    }
}
