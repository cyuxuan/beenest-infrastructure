package org.apereo.cas.beenest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 用户注册请求 DTO
 */
@Data
public class UserRegisterDTO {

    /** 用户名 */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 密码 */
    @NotBlank(message = "密码不能为空")
    private String password;

    /** 昵称 */
    private String nickname;

    /** 手机号 */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 邮箱 */
    private String email;

    /** 用户类型：CUSTOMER/PILOT/ADMIN */
    private String userType = "CUSTOMER";

    /** 目标应用 ID（可选，注册后自动赋权） */
    private Long serviceId;
}
