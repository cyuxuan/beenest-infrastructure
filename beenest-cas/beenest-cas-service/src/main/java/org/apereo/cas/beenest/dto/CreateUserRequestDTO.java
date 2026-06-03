package org.apereo.cas.beenest.dto;

import lombok.Data;

/**
 * 管理员创建用户请求 DTO
 */
@Data
public final class CreateUserRequestDTO {

    /** 用户名（必填） */
    private String username;

    /** 密码（必填） */
    private String password;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 名 */
    private String firstName;

    /** 姓 */
    private String lastName;

    /** 用户类型：CUSTOMER / PILOT */
    private String userType;
}