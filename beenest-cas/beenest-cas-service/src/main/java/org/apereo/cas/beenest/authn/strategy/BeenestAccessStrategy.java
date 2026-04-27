package org.apereo.cas.beenest.authn.strategy;

import org.apereo.cas.beenest.service.AppAccessService;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.services.DefaultRegisteredServiceAccessStrategy;
import org.apereo.cas.services.RegisteredServiceAccessStrategyRequest;
import org.apereo.cas.services.UnauthorizedServiceException;
import org.springframework.context.ApplicationContext;

/**
 * Beenest 自定义应用访问策略
 * <p>
 * 继承 Apereo CAS 的 DefaultRegisteredServiceAccessStrategy，
 * 在基础校验之上增加应用级访问控制：通过 AppAccessService 校验用户是否有权访问指定应用。
 * <p>
 * 使用 Spring ApplicationContext 延迟获取 AppAccessService，
 * 解决 JPA 反序列化 Service JSON 时 Spring Bean 不可用的问题。
 * 避免使用 static 字段直接持有 Service 实例导致的内存泄漏和 ClassLoader 问题。
 * <p>
 * 在服务定义 JSON 中使用：
 * <pre>
 * "accessStrategy": {
 *   "@class": "org.apereo.cas.beenest.authn.strategy.BeenestAccessStrategy",
 *   "enabled": true,
 *   "ssoEnabled": true
 * }
 * </pre>
 */
@Slf4j
public class BeenestAccessStrategy extends DefaultRegisteredServiceAccessStrategy {

    private static final long serialVersionUID = 1L;

    /**
     * Spring ApplicationContext 持有者
     * <p>
     * 在 CAS 启动时由配置类注入。反序列化时不依赖 Bean 注入，
     * 而是通过全局 Context 延迟查找 AppAccessService。
     * 使用 volatile 保证多线程可见性。
     */
    private static volatile ApplicationContext applicationContext;

    /**
     * 设置 Spring ApplicationContext（由配置类在启动时调用）
     */
    public static void setApplicationContext(ApplicationContext ctx) {
        applicationContext = ctx;
    }

    @Override
    public boolean authorizeRequest(final RegisteredServiceAccessStrategyRequest request) throws Throwable {
        // 1. 执行默认校验（enabled、ssoEnabled 等）
        boolean defaultResult = super.authorizeRequest(request);
        if (!defaultResult) {
            return false;
        }

        // 2. 延迟获取 AppAccessService（解决 JPA 反序列化时 Bean 不可用的问题）
        AppAccessService appAccessService = null;
        if (applicationContext != null) {
            try {
                appAccessService = applicationContext.getBean(AppAccessService.class);
            } catch (Exception e) {
                LOGGER.warn("无法获取 AppAccessService: {}", e.getMessage());
            }
        }

        // 3. 应用级访问控制校验
        if (appAccessService != null && request.getPrincipalId() != null) {
            String userId = request.getPrincipalId();
            Long serviceId = request.getRegisteredService().getId();

            boolean hasAccess = appAccessService.hasAccess(userId, serviceId);
            if (!hasAccess) {
                LOGGER.warn("用户无权访问该应用: userId={}, serviceId={}, service={}",
                    userId, serviceId, request.getRegisteredService().getName());
                throw UnauthorizedServiceException.denied("您没有访问该应用的权限");
            }
        }

        return true;
    }
}
