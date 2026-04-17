package org.apereo.cas.beenest.common.util;

import org.apache.commons.lang3.StringUtils;

import java.util.Set;

/**
 * 用户类型白名单工具。
 */
public final class UserTypeUtils {

    private static final Set<String> ALLOWED_USER_TYPES = Set.of("CUSTOMER", "PILOT", "ADMIN");

    private UserTypeUtils() {
    }

    public static String normalize(String userType) {
        String normalized = StringUtils.trimToEmpty(userType).toUpperCase();
        if (ALLOWED_USER_TYPES.contains(normalized)) {
            return normalized;
        }
        return "CUSTOMER";
    }
}
