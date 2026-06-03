package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.entity.UnifiedUserDO;
import java.util.*;

/**
 * 认证属性构建工具类
 * <p>从 cas_user 表的用户信息构建 Principal attributes 中的 memberOf 属性，
 * 用于 CAS 原生 DefaultRegisteredServiceAccessStrategy.requiredAttributes 匹配。</p>
 */
public final class BeenestPrincipalAttributesBuilder {

    private BeenestPrincipalAttributesBuilder() {}

    /**
     * 构建用户认证属性
     *
     * @param user 已认证的用户实体
     * @return Principal attributes，包含 memberOf 角色列表
     */
    public static Map<String, List<Object>> buildAttributes(UnifiedUserDO user) {
        var memberOf = new ArrayList<String>();
        // 1. 基于 user_type 的基础角色
        if (user.getUserType() != null) {
            switch (user.getUserType()) {
                case "ADMIN" -> memberOf.add("ROLE_ADMIN");
                case "PILOT" -> memberOf.add("ROLE_PILOT");
                default -> memberOf.add("ROLE_USER");
            }
        }
        // 2. 基于 roles 字段的应用角色
        if (user.getRoles() != null && !user.getRoles().isBlank()) {
            for (String role : user.getRoles().split(",")) {
                String trimmed = role.trim();
                if (!trimmed.isEmpty() && !memberOf.contains(trimmed)) {
                    memberOf.add(trimmed);
                }
            }
        }
        var attrs = new HashMap<String, List<Object>>();
        attrs.put("memberOf", List.copyOf(memberOf));

        // 3. 输出 tokenVersion，供 Client Starter 感知权限变更
        if (user.getTokenVersion() != null) {
            attrs.put("tokenVersion", List.of(String.valueOf(user.getTokenVersion())));
        }

        return Map.copyOf(attrs);
    }
}
