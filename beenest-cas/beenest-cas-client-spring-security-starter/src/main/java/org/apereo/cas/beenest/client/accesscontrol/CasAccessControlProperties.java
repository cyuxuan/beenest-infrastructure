package org.apereo.cas.beenest.client.accesscontrol;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAS 用户访问控制配置属性。
 *
 * @see CasUserAccessControlService
 */
@Data
@ConfigurationProperties(prefix = "cas.client.access-control")
public class CasAccessControlProperties {

    /** 是否启用访问控制 SPI（默认 false，向后兼容） */
    private boolean enabled = false;

    /** 本应用要求的 CAS 角色名（可覆盖 SPI 实现的 getRequiredRole()） */
    private String requiredRole;

    /** 有权限但无本地用户时是否自动创建（默认 true） */
    private boolean autoCreateOnGrant = true;

    /** 无权限但有本地用户时是否自动禁用（默认 true） */
    private boolean autoDisableOnRevoke = true;

    /** 禁用用户时是否同时强制下线（默认 true） */
    private boolean forceLogoutOnDisable = true;
}