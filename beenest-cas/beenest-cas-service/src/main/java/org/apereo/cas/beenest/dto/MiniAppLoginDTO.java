package org.apereo.cas.beenest.dto;

import lombok.Data;

/**
 * 小程序登录请求 DTO
 * <p>
 * 通用字段，适配微信/抖音/支付宝三个平台。
 * 各平台使用不同的授权码字段名。
 */
@Data
public final class MiniAppLoginDTO {

    /** 微信授权码（微信小程序登录时必填） */
    private String code;

    /** 抖音授权码（抖音小程序登录时必填） */
    private String douyinCode;

    /** 支付宝授权码（支付宝小程序登录时必填） */
    private String authCode;

    /** 手机号授权码（支付宝/微信一键登录时使用） */
    private String phoneCode;

    /** 用户类型：CUSTOMER / PILOT */
    private String userType;

    /** 昵称 */
    private String nickname;
}
