package org.apereo.cas.beenest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * CAS 统一用户实体
 * <p>
 * 对应 cas_user 表，支持多种认证方式（微信、抖音、支付宝、用户名密码、短信、APP）。
 */
@Data
public class UnifiedUserDO {

    private Long id;
    private String userId;
    private String userType;
    private String identity;
    private String source;
    private String loginType;

    // 微信
    private String openid;
    private String unionid;

    // 抖音
    private String douyinOpenid;
    private String douyinUnionid;

    // 支付宝
    private String alipayUid;
    private String alipayOpenid;

    // 基础信息
    private String username;
    private String nickname;
    private String avatarUrl;
    private String phone;
    private String email;
    private String passwordHash;
    private Boolean phoneVerified;
    private Boolean emailVerified;

    // MFA
    private Boolean mfaEnabled;
    private String mfaSecretEncrypted;

    // 状态与安全
    private Integer status;
    private Integer failedLoginCount;
    private LocalDateTime lockUntilTime;

    // 登录追踪
    private LocalDateTime lastLoginTime;
    private String lastLoginIp;
    private String lastLoginUa;
    private String lastLoginDevice;
    private Integer tokenVersion;

    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
