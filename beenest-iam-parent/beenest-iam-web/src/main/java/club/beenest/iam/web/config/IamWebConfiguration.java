package club.beenest.iam.web.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;

import java.nio.charset.StandardCharsets;

/**
 * Web 模块配置:主题模板解析器与页面语言切换拦截器。
 * <p>Thymeleaf 模板按 {@code classpath:/themes/{theme}/{page}/{page}.html} 解析,
 * 视图名的主题段由 {@link IamThemeSelector} 按请求的身份域动态拼接
 * (域主题 → 全局主题);静态资源 {@code /theme-static/**} 由
 * {@code club.beenest.iam.web.controller.ThemeStaticController} 按同一顺序动态提供。</p>
 */
@Configuration
@EnableConfigurationProperties(IamThemeProperties.class)
public class IamWebConfiguration implements WebMvcConfigurer {

    private final LocaleChangeInterceptor localeChangeInterceptor;

    public IamWebConfiguration(@Qualifier("localeChangeInterceptor") LocaleChangeInterceptor localeChangeInterceptor) {
        this.localeChangeInterceptor = localeChangeInterceptor;
    }

    /**
     * 控制台模板解析器:console/index.html。
     * <p>前缀取 {@code classpath:/},视图名自带目录({@code console/index}),
     * 与主题解析器(前缀 {@code themes/{active}/})互不干扰。</p>
     *
     * @param applicationContext Spring 上下文,解析器读取资源用
     * @return 控制台模板解析器
     */
    @Bean
    public SpringResourceTemplateResolver consoleTemplateResolver(ApplicationContext applicationContext) {
        SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
        resolver.setApplicationContext(applicationContext);
        resolver.setPrefix("classpath:/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 顺序先于主题:控制台模板名带 console/ 前缀,不会与主题页冲突
        resolver.setOrder(0);
        // 校验资源存在:无 checkExistence 时该解析器会把 login/login 这类
        // 主题视图抢先解析为不存在的路径
        resolver.setCheckExistence(true);
        return resolver;
    }

    /**
     * 主题模板解析器:login/login.html、error/error.html 等。
     * <p>前缀只到 {@code themes/},主题段由视图名携带({@code {theme}/login/login}):
     * 解析器实例全局共享,前缀不能随请求变化,按域选主题的职责落在视图名上。</p>
     *
     * @param applicationContext Spring 上下文,解析器读取资源用
     * @return 主题模板解析器
     */
    @Bean
    public SpringResourceTemplateResolver themeTemplateResolver(ApplicationContext applicationContext) {
        SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
        resolver.setApplicationContext(applicationContext);
        resolver.setPrefix("classpath:/themes/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 与 Boot 默认的 templates/ 解析器共存,优先级更高
        resolver.setOrder(1);
        // 校验资源存在:视图名指向不存在的目录时交给 Boot 默认解析器
        // 给出可读错误,不缓存不存在的路径
        resolver.setCheckExistence(true);
        return resolver;
    }

    /**
     * 模板引擎:注册控制台与主题两个解析器(按 order 匹配)。
     *
     * @param consoleTemplateResolver 控制台解析器
     * @param themeTemplateResolver   主题解析器
     * @return Thymeleaf 模板引擎
     */
    @Bean
    public SpringTemplateEngine themeTemplateEngine(SpringResourceTemplateResolver consoleTemplateResolver,
                                                    SpringResourceTemplateResolver themeTemplateResolver) {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.addTemplateResolver(consoleTemplateResolver);
        engine.addTemplateResolver(themeTemplateResolver);
        return engine;
    }

    /**
     * 静态资源:
     * <ul>
     *   <li>{@code /theme-static/**} → 见 {@link ThemeStaticController}(按域动态选主题,无法用固定映射);</li>
     *   <li>{@code /console-static/**} → 管理控制台资源,与主题解耦;</li>
     *   <li>{@code /portal-static/**} → 用户门户资源,与控制台、主题解耦。</li>
     * </ul>
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/console-static/**")
                .addResourceLocations("classpath:/console/static/");
        registry.addResourceHandler("/portal-static/**")
                .addResourceLocations("classpath:/portal/static/");
    }

    /**
     * 注册语言切换拦截器。
     * <p>只有带 {@code ?lang=zh-CN} / {@code ?lang=en} 的请求会改写语言并写入 Cookie;
     * 不带参数的请求沿用 {@link club.beenest.iam.common.i18n.IamLocaleResolver} 解析出的语言。</p>
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor);
    }
}
