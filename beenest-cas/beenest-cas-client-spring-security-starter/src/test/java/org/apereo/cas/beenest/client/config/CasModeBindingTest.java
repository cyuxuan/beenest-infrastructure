package org.apereo.cas.beenest.client.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CAS 模式绑定测试。
 */
class CasModeBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    WebMvcAutoConfiguration.class))
            .withUserConfiguration(CasSecurityProperties.class);

    @Test
    void shouldBindLowerCaseYamlModeToEnum() {
        contextRunner
                .withPropertyValues(
                        "cas.client.mode=resource-server",
                        "cas.client.enabled=true")
                .run(context -> {
                    CasSecurityProperties properties = context.getBean(CasSecurityProperties.class);
                    assertThat(properties.getMode()).isEqualTo(CasMode.RESOURCE_SERVER);
                    assertThat(properties.isResourceServerMode()).isTrue();
                });
    }
}
