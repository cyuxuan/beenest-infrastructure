package club.beenest.payment.config;

import club.beenest.payment.interceptor.CallbackIpWhitelistInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 注册支付回调 IP 白名单拦截器，仅对回调端点生效
 *
 * @author System
 * @since 2026-04-08
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final CallbackIpWhitelistInterceptor callbackIpWhitelistInterceptor;

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(callbackIpWhitelistInterceptor)
                .addPathPatterns(
                        "/api/wallet/payment/callback/**",
                        "/api/wallet/payment/refund/callback/**"
                );
    }
}
