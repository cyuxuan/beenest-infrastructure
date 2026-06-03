package org.apereo.cas.beenest.vo;

import lombok.Data;

import java.util.List;

/**
 * 服务授权用户列表响应 VO
 */
@Data
public final class ServiceUsersVO {

    /** 服务 ID */
    private long serviceId;

    /** 服务名称 */
    private String name;

    /** 访问所需角色 */
    private String requiredRole;

    /** 已授权用户列表 */
    private List<UserDetailVO> users;

    /** 是否开放访问 */
    private boolean openAccess;
}