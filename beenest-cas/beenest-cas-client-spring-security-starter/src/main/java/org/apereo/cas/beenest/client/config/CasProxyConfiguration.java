package org.apereo.cas.beenest.client.config;

import org.apereo.cas.client.proxy.ProxyGrantingTicketStorage;
import org.apereo.cas.client.proxy.ProxyGrantingTicketStorageImpl;
import org.apereo.cas.client.validation.Cas20ProxyTicketValidator;
import org.apereo.cas.client.validation.ProxyList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

/**
 * Proxy Ticket 条件配置
 * <p>
 * 当 {@code cas.client.proxy.enabled=true} 时激活。
 * 注册 Cas20ProxyTicketValidator 替换默认的 Cas20ServiceTicketValidator。
 */
@Configuration
@ConditionalOnProperty(prefix = "cas.client.proxy", name = "enabled", havingValue = "true")
public class CasProxyConfiguration {

    @Bean
    public ProxyGrantingTicketStorage proxyGrantingTicketStorage() {
        return new ProxyGrantingTicketStorageImpl();
    }

    /**
     * 使用 @Primary 替换 CasCoreConfiguration 中的默认 TicketValidator
     */
    @Bean
    @Primary
    public Cas20ProxyTicketValidator cas20ProxyTicketValidator(
            CasSecurityProperties properties,
            ProxyGrantingTicketStorage pgtStorage) {
        Cas20ProxyTicketValidator validator = new Cas20ProxyTicketValidator(properties.getServerUrl());
        validator.setProxyCallbackUrl(properties.getProxy().getCallbackUrl());
        validator.setProxyGrantingTicketStorage(pgtStorage);
        // 如果配置了 trusted-proxies，使用 ProxyList 限制允许的代理链
        if (!properties.getProxy().getTrustedProxies().isEmpty()) {
            validator.setAcceptAnyProxy(false);
            List<String[]> chains = new ArrayList<>();
            for (String proxy : properties.getProxy().getTrustedProxies()) {
                chains.add(new String[]{proxy});
            }
            validator.setAllowedProxyChains(new ProxyList(chains));
        } else {
            validator.setAcceptAnyProxy(true);
        }
        return validator;
    }
}
