package org.apereo.cas.beenest.dto;

import lombok.Data;

/**
 * 用户更新请求 DTO
 * <p>
 * 只允许更新非敏感字段，password/status/tokenVersion 等不应通过此接口修改。
 */
@Data
public class UserUpdateDTO {

    private String nickname;
    private String phone;
    private String email;
    private String avatarUrl;
    private String userType;
}
