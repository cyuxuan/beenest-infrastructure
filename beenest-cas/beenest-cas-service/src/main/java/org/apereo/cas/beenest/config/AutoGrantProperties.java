package org.apereo.cas.beenest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Set;

/**
 * 自动赋权配置属性。
 * <p>
 * 新注册用户自动赋予指定 Service 对应的角色，实现「注册即授权」。
 * 角色名与 Service JSON 的 accessStrategy.requiredAttributes.memberOf 对应。
 */
@Data
@ConfigurationProperties(prefix = "beenest.user")
public class AutoGrantProperties {

    /** 自动赋权的 Service ID 集合 */
    private Set<Long> autoGrantServiceIds = Set.of(10001L);

    /** Service ID → 角色名映射，如 10001 → ROLE_DRONE_SYSTEM */
    private Map<Long, String> autoGrantRoles = Map.of(
            10001L, "ROLE_DRONE_SYSTEM",
            10003L, "ROLE_PAYMENT"
    );
}
