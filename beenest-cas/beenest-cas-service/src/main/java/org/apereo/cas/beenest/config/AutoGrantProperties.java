package org.apereo.cas.beenest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动赋权配置属性。
 * <p>
 * 新注册用户自动赋予指定 Service 对应的角色列表，实现「注册即授权」。
 * 角色名与 Service JSON 的 accessStrategy.requiredAttributes.memberOf 对应。
 * <p>
 * 配置采用列表格式，同角色的服务天然聚合，配置条目数 = 角色组合数而非服务数：
 * <pre>
 * beenest:
 *   user:
 *     auto-grant:
 *       - service-ids: [10001, 10002, 10004]
 *         roles: [ROLE_DRONE_SYSTEM, ROLE_PAYMENT]
 *       - service-ids: [10003]
 *         roles: [ROLE_PAYMENT]
 * </pre>
 * <p>
 * 含义：当用户通过 serviceId=10001/10002/10004 的服务注册时，
 * 自动授予 ROLE_DRONE_SYSTEM + ROLE_PAYMENT 两个角色，
 * 使该用户同时能访问 drone-system 和 beenest-payment。
 */
@Data
@ConfigurationProperties(prefix = "beenest.user")
public class AutoGrantProperties {

    /** 自动赋权规则列表 */
    private List<AutoGrantRule> autoGrant = new ArrayList<>();

    /**
     * 单条自动赋权规则：一组 serviceId 共享相同的角色列表。
     */
    @Data
    public static class AutoGrantRule {

        /** 共享同一角色列表的 Service ID 集合 */
        private List<Long> serviceIds = new ArrayList<>();

        /** 注册时自动授予的角色列表（可跨应用） */
        private List<String> roles = new ArrayList<>();
    }
}
