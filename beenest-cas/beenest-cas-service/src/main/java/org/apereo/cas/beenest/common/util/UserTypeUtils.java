package org.apereo.cas.beenest.common.util;

import org.apache.commons.lang3.StringUtils;

import java.util.Set;

/**
 * 用户类型白名单工具。
 */
public final class UserTypeUtils {

    private static final Set<String> ALLOWED_USER_TYPES = Set.of("CUSTOMER", "PILOT", "ADMIN");
    private static final Set<String> SELF_REGISTRATION_USER_TYPES = Set.of("CUSTOMER", "PILOT");

    private UserTypeUtils() {
    }

    /**
     * 归一化系统内可信来源传入的用户类型。
     *
     * @param userType 用户类型
     * @return 允许的用户类型，非法值回退为 CUSTOMER
     */
    public static String normalize(String userType) {
        String normalized = StringUtils.trimToEmpty(userType).toUpperCase();
        if (ALLOWED_USER_TYPES.contains(normalized)) {
            return normalized;
        }
        return "CUSTOMER";
    }

    /**
     * 归一化客户端/自助注册传入的用户类型。
     * <p>
     * ADMIN 只能由后台管理或数据侧授予，不能通过客户端注册写入。
     *
     * @param userType 用户类型
     * @return CUSTOMER 或 PILOT，非法值回退为 CUSTOMER
     */
    public static String normalizeSelfRegistration(String userType) {
        String normalized = StringUtils.trimToEmpty(userType).toUpperCase();
        if (SELF_REGISTRATION_USER_TYPES.contains(normalized)) {
            return normalized;
        }
        return "CUSTOMER";
    }
}
