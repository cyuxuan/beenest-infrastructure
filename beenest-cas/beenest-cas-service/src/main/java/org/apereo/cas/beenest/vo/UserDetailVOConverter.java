package org.apereo.cas.beenest.vo;

import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * UnifiedUserDO → UserDetailVO 转换器
 */
public final class UserDetailVOConverter {

    private UserDetailVOConverter() {}

    /**
     * 将 UnifiedUserDO 转换为 UserDetailVO
     */
    public static UserDetailVO toVO(UnifiedUserDO user) {
        UserDetailVO vo = new UserDetailVO();
        vo.setUserId(user.getUserId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setUserType(user.getUserType());
        vo.setStatus(user.getStatus());
        vo.setRoles(parseRoles(user.getRoles()));
        vo.setLastLoginTime(user.getLastLoginTime() != null ? user.getLastLoginTime().toString() : null);
        vo.setCreatedTime(user.getCreatedTime() != null ? user.getCreatedTime().toString() : null);
        vo.setFailedLoginCount(user.getFailedLoginCount());
        vo.setLockUntilTime(user.getLockUntilTime() != null ? user.getLockUntilTime().toString() : null);
        return vo;
    }

    /**
     * 解析逗号分隔的角色字符串为列表
     */
    public static List<String> parseRoles(String roles) {
        if (StringUtils.isBlank(roles)) {
            return List.of();
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .toList();
    }
}