package org.apereo.cas.beenest.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 应用注册/更新 DTO
 */
@Data
public class CasServiceRegisterDTO {

    /** 应用名称 */
    @NotBlank(message = "应用名称不能为空")
    private String name;

    /** 服务 URL 匹配模式（正则） */
    @NotBlank(message = "服务URL模式不能为空")
    private String serviceId;

    /** 应用描述 */
    private String description;

    /** 登出回调 URL */
    private String logoutUrl;

    /** 登出类型：BACK_CHANNEL / FRONT_CHANNEL */
    private String logoutType = "BACK_CHANNEL";

    /** 返回给应用的用户属性列表 */
    private List<String> allowedAttributes;

    /**
     * 允许的认证方式列表。
     * 为空表示允许所有认证方式。
     * 可选值：USERNAME_PASSWORD, WECHAT, DOUYIN_MINI, ALIPAY_MINI, PHONE_SMS, APP
     */
    private List<String> allowedAuthenticationHandlers;

    /** 是否启用应用级访问控制 */
    private Boolean accessControlEnabled;

    /** 应用 ID（更新时使用，注册时可选） */
    private Long id;
}
