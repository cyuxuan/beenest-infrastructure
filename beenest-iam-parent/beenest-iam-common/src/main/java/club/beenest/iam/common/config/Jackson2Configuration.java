package club.beenest.iam.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 2 配置。
 * <p>工程的 Redis 对象序列化依赖 Jackson 2,此处单独提供 {@link ObjectMapper} Bean,
 * 与 Spring Boot 默认的 Jackson 3 并存。</p>
 */
@Configuration
public class Jackson2Configuration {

    /**
     * Jackson 2 映射器(用于 Redis 序列化等场景)。
     *
     * @return 忽略未知字段的 Jackson 2 映射器
     */
    @Bean
    public ObjectMapper jackson2ObjectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
