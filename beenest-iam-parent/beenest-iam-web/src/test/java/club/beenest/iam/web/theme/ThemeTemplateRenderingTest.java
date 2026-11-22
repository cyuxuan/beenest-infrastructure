package club.beenest.iam.web.theme;

import club.beenest.iam.authz.account.AccountOverview;
import club.beenest.iam.authz.rest.dto.WebAuthnCredentialResponse;
import club.beenest.iam.web.config.IamPageModelAdvice;
import club.beenest.iam.web.config.IamTemplateFormatter;
import club.beenest.iam.web.controller.AccountPageController;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.IWebContext;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.messageresolver.SpringMessageResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 主题模板渲染测试。
 * <p>主题页只在运行时渲染,编译期发现不了语法错误、缺失的文案键、片段路径写错。
 * 本测试用真实模板引擎渲染全部主题页,中英各一遍,把这类问题挡在提交前。</p>
 * <p>不启动 Spring 容器:模板、文案、片段都从 classpath 读取,渲染结果与线上一致。</p>
 */
@DisplayName("主题模板渲染")
class ThemeTemplateRenderingTest {

    /** 主题目录名,与生产配置一致 */
    private static final String THEME = "nestid";

    private static SpringTemplateEngine engine;

    /** 文案源,与引擎共用同一份资源 */
    private static ResourceBundleMessageSource bundle;

    @BeforeAll
    static void setUpEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("themes/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        // 测试期间关闭缓存,避免不同用例之间串模板
        resolver.setCacheable(false);

        bundle = new ResourceBundleMessageSource();
        bundle.setBasenames("i18n/messages");
        bundle.setDefaultEncoding("UTF-8");
        bundle.setFallbackToSystemLocale(false);
        bundle.setUseCodeAsDefaultMessage(false);

        SpringMessageResolver messageResolver = new SpringMessageResolver();
        messageResolver.setMessageSource(bundle);

        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setMessageResolver(messageResolver);
    }

    /**
     * 渲染一个主题页。
     *
     * @param page     页面视图名(不含主题前缀),如 {@code login/login}
     * @param locale   渲染语言
     * @param variables 页面模型变量
     * @return 渲染后的 HTML
     */
    private String render(String page, Locale locale, Map<String, Object> variables) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockServletContext servletContext = new MockServletContext();
        Map<String, Object> vars = new HashMap<>(variables);
        vars.put("themeName", THEME);
        vars.put("langSwitch", langSwitch(locale));
        vars.put("fmt", new IamTemplateFormatter());
        IWebExchange exchange = JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(request, response);
        IWebContext context = new WebContext(exchange, locale, vars);
        return engine.process(THEME + "/" + page, context);
    }

    /**
     * 构造语言切换数据。
     *
     * @param locale 当前语言
     * @return 语言切换模型
     */
    private IamPageModelAdvice.LangSwitch langSwitch(Locale locale) {
        boolean chinese = Locale.SIMPLIFIED_CHINESE.getLanguage().equals(locale.getLanguage());
        return new IamPageModelAdvice.LangSwitch(
                chinese ? "zh-CN" : "en",
                chinese ? "en" : "zh-CN",
                chinese ? "English" : "中文",
                "/login?lang=" + (chinese ? "en" : "zh-CN"));
    }

    /** 登录页:口令 + 短信 + Passkey 三块都要渲染出来。 */
    private Map<String, Object> loginModel() {
        Map<String, Object> model = new HashMap<>();
        model.put("realmKey", "default");
        model.put("clientId", "demo");
        model.put("returnTo", "");
        model.put("lastUsername", "alice");
        model.put("loginFailed", true);
        model.put("errorMessage", "");
        model.put("passwordLoginVisible", true);
        model.put("smsLoginEnabled", true);
        model.put("passkeyAvailable", true);
        return model;
    }

    @Test
    @DisplayName("登录页中英文均渲染成功,且文案随语言切换")
    void loginPageRendersInBothLanguages() {
        String zh = render("login/login", Locale.SIMPLIFIED_CHINESE, loginModel());
        assertThat(zh).contains("欢迎登录").contains("密码登录").contains("使用 Passkey 登录");
        assertThat(zh).contains("lang=\"zh\"");
        assertThat(zh).contains("/login?lang=en");

        String en = render("login/login", Locale.US, loginModel());
        assertThat(en).contains("Welcome").contains("Password").contains("Sign in with a Passkey");
        assertThat(en).doesNotContain("欢迎登录");
    }

    @Test
    @DisplayName("登录页脚本文案走服务端注入,未注入时保留字面量兜底")
    void loginPageScriptMessagesAreInjected() {
        String en = render("login/login", Locale.US, loginModel());
        assertThat(en).contains("Unable to start Passkey sign-in");
        // 占位符必须原样保留,由脚本在运行时替换
        assertThat(en).contains("Sign in with {0}");
    }

    @Test
    @DisplayName("二步验证页中英文均渲染成功")
    void mfaPageRenders() {
        Map<String, Object> model = new HashMap<>();
        model.put("realmKey", "default");
        model.put("mfaToken", "token");
        model.put("enrollMode", true);
        assertThat(render("login/mfa", Locale.SIMPLIFIED_CHINESE, model)).contains("二步验证").contains("验 证");
        assertThat(render("login/mfa", Locale.US, model)).contains("Two-step verification").contains("Verify");
    }

    @Test
    @DisplayName("补充验证页渲染二步验证与口令两种分支")
    void challengePageRenders() {
        Map<String, Object> model = new HashMap<>();
        model.put("realmKey", "default");
        model.put("clientId", "demo");
        model.put("challengeToken", "ct");
        model.put("mfaToken", "");
        model.put("requireMfa", false);
        model.put("smsAllowed", true);
        model.put("allowedMethods", Set.of("PASSWORD"));
        model.put("idps", List.of(new Idp("ldap", "LDAP 目录")));

        String zh = render("login/challenge", Locale.SIMPLIFIED_CHINESE, model);
        assertThat(zh).contains("补充验证").contains("完成验证").contains("使用 LDAP 目录 验证");

        String en = render("login/challenge", Locale.US, model);
        assertThat(en).contains("Step-up verification").contains("Verify with LDAP");
    }

    @Test
    @DisplayName("同意页按 scope 渲染名称与说明")
    void consentPageRenders() {
        Map<String, Object> model = new HashMap<>();
        model.put("realmKey", "default");
        model.put("clientId", "demo");
        model.put("clientName", "示例应用");
        model.put("state", "s1");
        model.put("principalName", "alice");
        model.put("scopes", List.of(
                scope("openid", "true", Locale.SIMPLIFIED_CHINESE),
                scope("profile", "false", Locale.SIMPLIFIED_CHINESE)));
        String zh = render("consent/consent", Locale.SIMPLIFIED_CHINESE, model);
        assertThat(zh).contains("身份标识").contains("基本资料").contains("同意并继续");

        model.put("scopes", List.of(
                scope("openid", "true", Locale.US),
                scope("profile", "false", Locale.US)));
        String en = render("consent/consent", Locale.US, model);
        assertThat(en).contains("Identity").contains("Basic profile").contains("Allow and continue");
    }

    @Test
    @DisplayName("错误页按状态码渲染对应提示")
    void errorPageRenders() {
        Map<String, Object> model = new HashMap<>();
        model.put("status", 404);
        model.put("kicker", "PAGE NOT FOUND");
        model.put("heading", "页面不存在");
        model.put("message", "你访问的页面不存在,或已被移动。");
        model.put("hintTitle", "链接可能已失效");
        model.put("hint", "请检查地址是否完整;如果是从收藏夹或旧邮件进入,请回到应用门户重新打开。");
        assertThat(render("error/error", Locale.SIMPLIFIED_CHINESE, model))
                .contains("PAGE NOT FOUND")
                .contains("页面不存在")
                .contains("链接可能已失效")
                .contains("返回应用门户")
                .contains("重新登录");
    }

    @Test
    @DisplayName("403 错误页点名被拒绝的应用并给出联系管理员的指引")
    void forbiddenErrorPageNamesApp() {
        Map<String, Object> model = new HashMap<>();
        model.put("status", 403);
        model.put("kicker", "ACCESS DENIED");
        model.put("heading", "当前账号无访问权限");
        model.put("message", "你已登录,但当前账号没有访问「无人机系统」的权限。");
        model.put("hintTitle", "需要访问这个应用?");
        model.put("hint", "请联系所属身份域的管理员,为你的账号开通该应用的访问权限。");
        model.put("appName", "无人机系统");
        assertThat(render("error/error", Locale.SIMPLIFIED_CHINESE, model))
                .contains("ACCESS DENIED")
                .contains("无人机系统")
                .contains("联系")
                .contains("管理员");
    }

    @Test
    @DisplayName("账户自助四页均渲染成功")
    void accountPagesRender() {
        Map<String, Object> forgot = new HashMap<>();
        forgot.put("realmKey", "default");
        forgot.put("mailAvailable", false);
        assertThat(render("account/forgot-password", Locale.SIMPLIFIED_CHINESE, forgot))
                .contains("找回口令").contains("发送重置链接");
        assertThat(render("account/forgot-password", Locale.US, forgot))
                .contains("Forgot password").contains("Send reset link");

        Map<String, Object> reset = new HashMap<>();
        reset.put("realmKey", "default");
        reset.put("ticket", "");
        reset.put("ticketMissing", true);
        assertThat(render("account/reset-password", Locale.SIMPLIFIED_CHINESE, reset))
                .contains("设置新口令").contains("缺少重置票据");

        Map<String, Object> verify = new HashMap<>();
        verify.put("realmKey", "default");
        verify.put("success", true);
        verify.put("message", "");
        assertThat(render("account/verify-email", Locale.US, verify))
                .contains("Verified").contains("Go to sign in");

        Map<String, Object> actions = new HashMap<>();
        actions.put("realmKey", "default");
        actions.put("mailAvailable", true);
        actions.put("passkeyAvailable", true);
        actions.put("actions", List.of(
                new AccountPageController.ActionItem("UPDATE_PASSWORD", "更新口令", "请设置新的登录口令。"),
                new AccountPageController.ActionItem("CONFIGURE_PASSKEY", "绑定 Passkey", "可以用指纹登录。")));
        assertThat(render("account/required-action", Locale.SIMPLIFIED_CHINESE, actions))
                .contains("完善账户信息").contains("更新口令").contains("立即绑定 Passkey");
    }

    @Test
    @DisplayName("用户中心渲染凭证、授权、设备与待办提示")
    void accountCenterRenders() {
        Map<String, Object> model = new HashMap<>();
        model.put("overview", accountOverview());
        model.put("realmKey", "default");
        model.put("notice", null);
        model.put("failed", false);

        String zh = render("account/account", Locale.SIMPLIFIED_CHINESE, model);
        assertThat(zh).contains("我的账户")
                .contains("登录口令").contains("动态口令").contains("Passkey")
                .contains("我的授权").contains("示例应用").contains("身份标识")
                .contains("登录设备").contains("本机")
                .contains("下载数据")
                .contains("还有未完成的账户事项")
                // 时间由模型里的格式化工具输出,不是 LocalDateTime.toString()
                .contains("2026-10-27 15:00")
                // 动态口令面板与恢复码区域默认隐藏,由脚本展开
                .contains("id=\"totp-panel\"").contains("aria-label=\"动态口令\"");

        String en = render("account/account", Locale.US, model);
        assertThat(en).contains("My account")
                .contains("My authorizations").contains("Signed-in devices")
                .contains("Download").contains("This device")
                .contains("Some account items are still pending")
                .doesNotContain("我的账户");
    }

    @Test
    @DisplayName("用户中心在无授权、无设备时不抛异常")
    void accountCenterRendersEmptySections() {
        Map<String, Object> model = new HashMap<>();
        model.put("overview", new AccountOverview(
                new AccountOverview.Profile("bob", null, null, false, null, false, "default", "INTERNAL"),
                new AccountOverview.Credentials(false, false, 0, false, List.of()),
                List.of(),
                List.of(),
                List.of()));
        model.put("realmKey", "default");
        model.put("notice", null);
        model.put("failed", true);

        String zh = render("account/account", Locale.SIMPLIFIED_CHINESE, model);
        assertThat(zh).contains("你还没有授权任何应用")
                .contains("当前没有其它登录设备")
                .contains("未填写")
                .contains("操作未完成");
    }

    @Test
    @DisplayName("全部主题页在两种语言下都不抛异常")
    void allPagesRenderWithoutError() {
        Map<String, Map<String, Object>> pages = new LinkedHashMap<>();
        pages.put("login/login", loginModel());

        Map<String, Object> mfa = new HashMap<>();
        mfa.put("realmKey", "default");
        mfa.put("mfaToken", "token");
        mfa.put("enrollMode", true);
        pages.put("login/mfa", mfa);

        Map<String, Object> challenge = new HashMap<>();
        challenge.put("realmKey", "default");
        challenge.put("clientId", "demo");
        challenge.put("challengeToken", "ct");
        challenge.put("mfaToken", "");
        challenge.put("requireMfa", false);
        challenge.put("smsAllowed", true);
        challenge.put("allowedMethods", Set.of("PASSWORD"));
        challenge.put("idps", List.of(new Idp("ldap", "LDAP")));
        pages.put("login/challenge", challenge);

        Map<String, Object> consent = new HashMap<>();
        consent.put("realmKey", "default");
        consent.put("clientId", "demo");
        consent.put("clientName", "demo");
        consent.put("state", "s1");
        consent.put("principalName", "alice");
        consent.put("scopes", List.of(scope("openid", "true", Locale.SIMPLIFIED_CHINESE)));
        pages.put("consent/consent", consent);

        Map<String, Object> error = new HashMap<>();
        error.put("status", 500);
        error.put("message", "");
        pages.put("error/error", error);

        Map<String, Object> forgot = new HashMap<>();
        forgot.put("realmKey", "default");
        forgot.put("mailAvailable", true);
        pages.put("account/forgot-password", forgot);

        Map<String, Object> reset = new HashMap<>();
        reset.put("realmKey", "default");
        reset.put("ticket", "t");
        reset.put("ticketMissing", false);
        pages.put("account/reset-password", reset);

        Map<String, Object> verify = new HashMap<>();
        verify.put("realmKey", "default");
        verify.put("success", false);
        verify.put("message", "");
        pages.put("account/verify-email", verify);

        Map<String, Object> actions = new HashMap<>();
        actions.put("realmKey", "default");
        actions.put("mailAvailable", true);
        actions.put("passkeyAvailable", true);
        actions.put("actions", List.of(new AccountPageController.ActionItem(
                "CONFIGURE_TOTP", "绑定二步验证", "绑定后可输入动态口令登录。")));
        pages.put("account/required-action", actions);

        Map<String, Object> account = new HashMap<>();
        account.put("overview", accountOverview());
        account.put("realmKey", "default");
        account.put("notice", "已撤销该应用的授权");
        account.put("failed", false);
        pages.put("account/account", account);

        pages.forEach((page, model) -> {
            for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
                assertThatCode(() -> render(page, locale, model))
                        .as("页面 %s 在语言 %s 下应能渲染", page, locale)
                        .doesNotThrowAnyException();
            }
        });
    }

    /**
     * 构造一份填满各类数据的用户中心模型。
     *
     * @return 含资料、凭证、授权、设备与待办的用户中心数据
     */
    private static AccountOverview accountOverview() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 15, 0);
        return new AccountOverview(
                new AccountOverview.Profile("alice", "爱丽丝", "alice@example.com", true,
                        "13800000000", true, "default", "INTERNAL"),
                new AccountOverview.Credentials(true, true, 8, true, List.of(
                        new WebAuthnCredentialResponse(1L, "MacBook Touch ID", "aaguid-1",
                                List.of("internal"), true, true, null, now))),
                List.of(new AccountOverview.ConsentItem("demo", "示例应用",
                        List.of(new AccountOverview.ScopeItem("openid", "身份标识")), now)),
                List.of(new AccountOverview.SessionItem("sid-1", "PASSWORD", "127.0.0.1",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36", now, now, true)),
                List.of("CONFIGURE_TOTP"));
    }

    /**
     * 构造同意页的 scope 行。
     *
     * @param code     scope 代码
     * @param required 是否必选({@code "true"} / {@code "false"})
     * @return 与生产代码结构一致的模型行
     */
    private static Map<String, String> scope(String code, String required, Locale locale) {
        Map<String, String> row = new HashMap<>();
        row.put("code", code);
        row.put("label", bundle.getMessage("page.consent.scope." + code + ".label", null, locale));
        row.put("description", bundle.getMessage("page.consent.scope." + code + ".desc", null, locale));
        row.put("required", required);
        return row;
    }

    /**
     * 补充验证页的联合登录提供方。
     *
     * @param alias       提供方别名
     * @param displayName 展示名
     */
    private record Idp(String alias, String displayName) {
    }
}
