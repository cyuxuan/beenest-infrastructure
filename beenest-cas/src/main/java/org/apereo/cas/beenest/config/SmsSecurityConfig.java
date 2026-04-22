package org.apereo.cas.beenest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apereo.cas.beenest.filter.SmsSendEndpointFilter;
import org.apereo.cas.beenest.service.SmsService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.Ordered;

/**
 * 短信接口安全配置
 * <p>
 * 使用最高优先级的 servlet filter 直接处理 `/cas/sms/send`，
 * 避免被 CAS 默认安全链提前拒绝。
 */
@AutoConfiguration
public class SmsSecurityConfig {

    @Bean
    public FilterRegistrationBean<SmsSendEndpointFilter> smsSendEndpointFilter(
            SmsService smsService,
            ObjectMapper objectMapper) {
        FilterRegistrationBean<SmsSendEndpointFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new SmsSendEndpointFilter(smsService, objectMapper));
        registration.addUrlPatterns("/sms/send");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("smsSendEndpointFilter");
        return registration;
    }
}
