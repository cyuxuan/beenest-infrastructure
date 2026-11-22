package club.beenest.iam.common.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * 国际化装配。
 * <p>注册文案源、语言解析器与语言切换拦截器,文案文件位于 {@code classpath:i18n/messages*}。</p>
 */
@Configuration
public class IamI18nConfiguration {

    /**
     * 文案源。
     *
     * @return 加载 {@code i18n/messages} 的文案源
     */
    @Bean("messageSource")
    public MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        // 不按系统语言回落,避免命中非预期的资源文件
        source.setFallbackToSystemLocale(false);
        // 键缺失时返回 null,由调用方决定回落方式
        source.setUseCodeAsDefaultMessage(false);
        // 静态工厂通过注册获取文案源
        IamMessages.register(source);
        return source;
    }

    /**
     * 语言解析器。
     *
     * @return 语言解析器
     */
    @Bean("localeResolver")
    public LocaleResolver localeResolver() {
        return new IamLocaleResolver();
    }

    /**
     * 语言切换拦截器:识别 {@code ?lang=} 参数并交给解析器记录。
     *
     * @return 语言切换拦截器
     */
    @Bean("localeChangeInterceptor")
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        // 仅允许 GET 请求切换语言
        interceptor.setHttpMethods("GET");
        return interceptor;
    }
}
