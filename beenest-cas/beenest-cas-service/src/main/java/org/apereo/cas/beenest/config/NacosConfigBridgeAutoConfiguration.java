package org.apereo.cas.beenest.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Nacos 远程配置桥接自动配置。
 * <p>
 * CAS 的 {@code casCompositePropertySource} 在 bootstrap 阶段创建，
 * 从本地文件和 classpath 加载配置，YAML 中的 ${DB_HOST:localhost} 占位符
 * 不被 CAS 的 YAML 解析器解析，直接使用默认值 localhost。
 * <p>
 * 本初始化器在主 application context 初始化时执行，
 * 将 Environment 中已有的 Nacos ConfigData PropertySource
 * 插入到 {@code bootstrapProperties-casCompositePropertySource} 之前，
 * 确保 Nacos 远程配置优先级高于 CAS 的本地兜底值。
 * <p>
 * 注册方式：通过 {@code spring.factories} 注册为
 * {@code org.springframework.context.ApplicationContextInitializer}。
 * <p>
 * 注意：本初始化器会被调用两次——
 * 第一次在 bootstrap context 中（此时 Nacos 尚未加载，自动跳过），
 * 第二次在主 application context 中（此时 Nacos ConfigData 已加载，执行桥接）。
 */
public class NacosConfigBridgeAutoConfiguration
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigBridgeAutoConfiguration.class);

    /**
     * Nacos ConfigData PropertySource 的名称前缀。
     * Spring Cloud Alibaba 2025.0.0.0 的 ConfigData 模式
     * 使用的名称格式为 {@code DEFAULT_GROUP@dataId}，
     * 例如 {@code DEFAULT_GROUP@beenest-cas.yml}。
     */
    private static final String NACOS_CONFIG_SOURCE_PREFIX = "DEFAULT_GROUP@";

    /** CAS bootstrap 复合配置 PropertySource 名称 */
    private static final String CAS_BOOTSTRAP_COMPOSITE_SOURCE_NAME = "bootstrapProperties-casCompositePropertySource";

    /** 桥接后的 PropertySource 名称 */
    private static final String BRIDGE_SOURCE_NAME = "nacosConfigBridgePropertySource";

    @Override
    public void initialize(final ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment environment = applicationContext.getEnvironment();
        MutablePropertySources propertySources = environment.getPropertySources();

        // 1. 收集所有 Nacos ConfigData PropertySource
        CompositePropertySource nacosSources = new CompositePropertySource(BRIDGE_SOURCE_NAME);
        int count = 0;

        for (PropertySource<?> propertySource : propertySources) {
            if (propertySource.getName().startsWith(NACOS_CONFIG_SOURCE_PREFIX)) {
                log.info("桥接 Nacos 远程配置 PropertySource: {}", propertySource.getName());
                nacosSources.addFirstPropertySource(propertySource);
                count++;
            }
        }

        if (count == 0) {
            log.debug("未找到 Nacos ConfigData PropertySource，跳过桥接（可能在 bootstrap context 中）");
            return;
        }

        log.info("共发现 {} 个 Nacos 远程配置 PropertySource", count);

        // 2. 将 Nacos PropertySource 插入到 CAS bootstrap 复合配置之前
        if (propertySources.contains(CAS_BOOTSTRAP_COMPOSITE_SOURCE_NAME)) {
            log.info("将 Nacos 远程配置插入到 {} 之前", CAS_BOOTSTRAP_COMPOSITE_SOURCE_NAME);
            propertySources.addBefore(CAS_BOOTSTRAP_COMPOSITE_SOURCE_NAME, nacosSources);
        } else {
            log.info("CAS bootstrap PropertySource 不存在，将 Nacos 远程配置插入到 Environment 顶部");
            propertySources.addFirst(nacosSources);
        }

        // 3. 验证关键配置值
        String dbUrl = environment.getProperty("cas.service-registry.jpa.url");
        String springDatasourceUrl = environment.getProperty("spring.datasource.url");
        log.info("桥接后 cas.service-registry.jpa.url = {}", dbUrl);
        log.info("桥接后 spring.datasource.url = {}", springDatasourceUrl);
    }
}
