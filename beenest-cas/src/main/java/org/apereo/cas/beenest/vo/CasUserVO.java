package org.apereo.cas.beenest.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户视图对象（脱敏）
 * <p>
 * 不包含 passwordHash、mfaSecretEncrypted 等敏感字段。
 */
@Data
public class CasUserVO {

    private String userId;
    private String username;
    private String nickname;
    private String avatarUrl;
    private String phone;
    private String email;
    private String userType;
    private String source;
    private String loginType;
    private Boolean phoneVerified;
    private Boolean emailVerified;
    private Boolean mfaEnabled;
    private Integer status;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createdTime;

    /**
     * 从 DO 转换为 VO（脱敏处理）
     */
    public static CasUserVO fromDO(Object obj) {
        if (!(obj instanceof org.apereo.cas.beenest.entity.UnifiedUserDO user)) {
            return null;
        }
        CasUserVO vo = new CasUserVO();
        vo.setUserId(user.getUserId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        // 手机号脱敏：138****1234
        vo.setPhone(maskPhone(user.getPhone()));
        vo.setEmail(maskEmail(user.getEmail()));
        vo.setUserType(user.getUserType());
        vo.setSource(user.getSource());
        vo.setLoginType(user.getLoginType());
        vo.setPhoneVerified(user.getPhoneVerified());
        vo.setEmailVerified(user.getEmailVerified());
        vo.setMfaEnabled(user.getMfaEnabled());
        vo.setStatus(user.getStatus());
        vo.setLastLoginTime(user.getLastLoginTime());
        vo.setCreatedTime(user.getCreatedTime());
        return vo;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return email;
        String[] parts = email.split("@");
        return parts[0].substring(0, Math.min(2, parts[0].length())) + "***@" + parts[1];
    }
}
