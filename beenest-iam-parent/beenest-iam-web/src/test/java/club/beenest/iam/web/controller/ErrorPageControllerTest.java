package club.beenest.iam.web.controller;

import club.beenest.iam.authz.authorization.ClientApplicationAccessFilter;
import club.beenest.iam.common.theme.ThemeRegistry;
import club.beenest.iam.web.config.IamThemeProperties;
import club.beenest.iam.web.config.IamThemeSelector;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误页控制器测试。
 * <p>页面文案按状态码与拒绝应用装配;没有错误上下文的直接访问(错误页上的语言切换)
 * 不能渲染成 500,否则一次普通的语言切换看起来像服务故障。</p>
 */
class ErrorPageControllerTest {

    private final IamThemeSelector themeSelector = new IamThemeSelector(new IamThemeProperties(),
            new ThemeRegistry());
    private final ResourceBundleMessageSource messageSource = messageSource();
    private final ErrorPageController controller = new ErrorPageController(themeSelector, messageSource);

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("没有错误上下文的直接访问回到入口页")
    void directAccessRedirectsToEntry() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");

        assertThat(controller.errorPage(request, new ExtendedModelMap())).isEqualTo("redirect:/");
    }

    @Test
    @DisplayName("403 页面点名被拒绝的应用并给出联系管理员的指引")
    void forbiddenPageNamesDeniedApp() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 403);
        request.setAttribute(ClientApplicationAccessFilter.DENIED_APP_NAME_ATTRIBUTE, "云Go 飞手联盟管理后台");
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.errorPage(request, model)).isEqualTo("nestid/error/error");
        assertThat(model.get("status")).isEqualTo(403);
        assertThat(model.get("kicker")).isEqualTo("ACCESS DENIED");
        assertThat(model.get("heading")).isEqualTo("当前账号无访问权限");
        assertThat(model.get("message").toString()).contains("云Go 飞手联盟管理后台");
        assertThat(model.get("hint").toString()).contains("管理员");
    }

    @Test
    @DisplayName("没有应用名称的 403 给出通用说明")
    void forbiddenPageWithoutAppUsesGenericMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 403);
        ExtendedModelMap model = new ExtendedModelMap();

        controller.errorPage(request, model);

        assertThat(model.get("message")).isEqualTo("你已登录,但当前账号没有被授予该页面的访问权限。");
    }

    @Test
    @DisplayName("400 不再显示「系统内部错误」,而是指出回调地址与登记值不一致这类可行动原因")
    void badRequestExplainsRegistrationMismatch() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
        ExtendedModelMap model = new ExtendedModelMap();

        controller.errorPage(request, model);

        assertThat(model.get("kicker")).isEqualTo("REQUEST REJECTED");
        assertThat(model.get("heading")).isEqualTo("请求参数不被接受");
        assertThat(model.get("message").toString()).contains("接入信息");
        // 授权请求 400 的最常见成因是回调地址不匹配,提示里必须点出来并给出比对口径,
        // 否则用户只能看到"系统内部错误",连往哪个方向查都不知道
        assertThat(model.get("hint").toString()).contains("回调地址");
        assertThat(model.get("hint").toString()).contains("http");
        assertThat(model.get("hint").toString()).doesNotContain("系统内部错误");
    }

    /** 文案源:与页面共用同一份资源。 */
    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }
}