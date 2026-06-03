package org.apereo.cas.beenest.vo;

import lombok.Data;

import java.util.List;

/**
 * 用户详情响应 VO
 */
@Data
public final class UserDetailVO {

    /** 用户 ID */
    private String userId;

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 手机号 */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 用户类型 */
    private String userType;

    /** 状态：1-正常，2-锁定，0-禁用 */
    private Integer status;

    /** 角色列表 */
    private List<String> roles;

    /** 最后登录时间 */
    private String lastLoginTime;

    /** 创建时间 */
    private String createdTime;

    /** 连续登录失败次数 */
    private Integer failedLoginCount;

    /** 锁定截止时间 */
    private String lockUntilTime;
}