package org.apereo.cas.beenest.client.config;

/**
 * CAS Starter 运行模式。
 */
public enum CasMode {

    /**
     * 登录网关模式。
     */
    LOGIN_GATEWAY,

    /**
     * 资源服务模式。
     */
    RESOURCE_SERVER;

    /**
     * 判断是否为资源服务模式。
     *
     * @return true 表示资源服务模式
     */
    public boolean isResourceServer() {
        return this == RESOURCE_SERVER;
    }

    /**
     * 判断是否为登录网关模式。
     *
     * @return true 表示登录网关模式
     */
    public boolean isLoginGateway() {
        return this == LOGIN_GATEWAY;
    }
}
