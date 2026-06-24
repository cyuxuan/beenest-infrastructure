package org.apereo.cas.beenest.client.accesscontrol;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAS 用户访问控制配置属性。
 * <p>
 * requiredRole 统一从 SPI 实现 {@link CasUserAccessControlService#getRequiredRole()} 获取，
 * 不再支持配置覆盖，确保角色名与 CAS 服务端 accessStrategy 一致。
 *
 * @see CasUserAccessControlService
 */
@Data
@ConfigurationProperties(prefix = "cas.client.access-control")
public class CasAccessControlProperties {

    /** 是否启用访问控制 SPI（默认 false，向后兼容） */
    private boolean enabled = false;

    /** 有权限但无本地用户时是否自动创建（默认 true） */
    private boolean autoCreateOnGrant = true;

    /** 无权限但有本地用户时是否自动禁用（默认 true） */
    private boolean autoDisableOnRevoke = true;

    /** 禁用用户时是否同时强制下线（默认 true） */
    private boolean forceLogoutOnDisable = true;
}
