package club.beenest.payment.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * HTTP 客户端配置。
 * <p>
 * 为需要调用外部或跨服务接口的组件提供统一的 RestTemplate，
 * 便于设置连接和读取超时。
 * </p>
 */
@Configuration
public class HttpClientConfig {

    /**
     * 创建带超时的 RestTemplate。
     *
     * @param builder Spring Boot 提供的 RestTemplate 构建器
     * @return RestTemplate 实例
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }
}
