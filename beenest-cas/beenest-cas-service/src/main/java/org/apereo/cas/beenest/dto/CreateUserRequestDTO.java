package org.apereo.cas.beenest.dto;

import org.apereo.cas.acct.AccountRegistrationRequest;
import org.apache.commons.lang3.StringUtils;
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

    /** 用户类型：CUSTOMER / PILOT / ADMIN（受信渠道允许 ADMIN） */
    private String userType;

    /**
     * 转换为 CAS 原生注册请求。
     * <p>
     * 供管理端点（/actuator/casUsers/create）与机器通道
     * （/api/admin/cas-users/create）共用，保证两处入参一致。
     *
     * @return CAS 原生 AccountRegistrationRequest
     */
    public AccountRegistrationRequest toRegistrationRequest() {
        AccountRegistrationRequest regRequest = new AccountRegistrationRequest();
        regRequest.getProperties().put("username", username);
        regRequest.getProperties().put("password", password);
        if (StringUtils.isNotBlank(email)) {
            regRequest.getProperties().put("email", email);
        }
        if (StringUtils.isNotBlank(phone)) {
            regRequest.getProperties().put("phone", phone);
        }
        if (StringUtils.isNotBlank(firstName)) {
            regRequest.getProperties().put("firstName", firstName);
        }
        if (StringUtils.isNotBlank(lastName)) {
            regRequest.getProperties().put("lastName", lastName);
        }
        if (StringUtils.isNotBlank(userType)) {
            regRequest.getProperties().put("userType", userType);
        }
        return regRequest;
    }
}