package org.apereo.cas.beenest.client.sync;

import org.apereo.cas.beenest.client.cache.BearerAuthorityVersionService;
import org.apereo.cas.beenest.client.cache.BearerTokenCache;
import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Bearer 权限缓存变更监听器。
 * <p>
 * 当收到 CAS 的用户变更事件时：
 * <ul>
 *   <li>清理本地 Bearer 认证缓存，避免旧权限继续生效</li>
 *   <li>若事件里带有权限版本，则同步写入权限版本服务，供其他节点感知</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class BearerAuthorityChangeListener implements CasUserChangeListener {

    private final CasSecurityProperties properties;
    private final BearerTokenCache bearerTokenCache;
    private final BearerAuthorityVersionService authorityVersionService;

    /**
     * 处理用户变更事件。
     *
     * @param event 用户变更事件
     */
    @Override
    public void onUserChange(UserChangeEvent event) {
        if (event == null || !StringUtils.hasText(event.getUserId())) {
            return;
        }

        // 1. 用户资料/权限发生变化后，先清理本地 token 缓存，避免继续使用旧权限
        bearerTokenCache.removeByUserId(event.getUserId());

        // 2. 如果事件里携带权限版本，则同步写入共享版本存储，帮助其他节点快速识别旧缓存
        String version = extractAuthorityVersion(event.getNewData());
        if (StringUtils.hasText(version)) {
            authorityVersionService.updateUserVersion(event.getUserId(), version);
            LOGGER.debug("已同步用户权限版本: userId={}, version={}", event.getUserId(), version);
        }
    }

    /**
     * 从用户变更数据中提取权限版本。
     *
     * @param newData 变更数据
     * @return 权限版本，未找到时返回 null
     */
    private String extractAuthorityVersion(Map<String, Object> newData) {
        if (newData == null || newData.isEmpty()) {
            return null;
        }

        String attributeKey = properties.getTokenAuth().getAuthorityVersionAttribute();
        Object value = newData.get(attributeKey);
        if (value == null) {
            return null;
        }
        String version = value.toString();
        return StringUtils.hasText(version) ? version.trim() : null;
    }
}
