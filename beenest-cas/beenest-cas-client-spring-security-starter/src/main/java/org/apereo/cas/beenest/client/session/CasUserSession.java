package org.apereo.cas.beenest.client.session;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.apereo.cas.client.authentication.AttributePrincipal;
import org.apereo.cas.client.validation.Assertion;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CAS 用户会话信息
 * <p>
 * 存储在 HttpSession 中，表示已通过 CAS 认证的用户。
 * 从 beenest-cas-client 迁移，保持字段完全兼容。
 * <p>
 * {@code attributes} 使用 {@code Map<String, Object>} 保留 CAS 返回的原始属性类型，
 * 多值属性（如 {@code memberOf}）保持为 {@code List<String>}，单值属性为 {@code String}。
 */
@Data
public class CasUserSession implements Serializable {

    public static final String SESSION_KEY = "CAS_USER_SESSION";

    private static final long serialVersionUID = 2L;

    /** 用户 ID */
    private String userId;

    /** 用户名 */
    private String username;

    /** 用户类型 */
    private String userType;

    /** 昵称 */
    private String nickname;

    /** 手机号 */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 头像 URL */
    private String avatarUrl;

    /** 身份标识 */
    private String identity;

    /** CAS 属性（ST 校验时返回的额外属性，多值属性保留为 List<String>） */
    private Map<String, Object> attributes;

    /** 认证时间（毫秒时间戳） */
    private long authTime;

    /** 关联的 ST（用于 SLO） */
    private String serviceTicket;

    /**
     * 从 JSON 数据增量更新字段
     */
    public void updateFromJson(JsonNode newData) {
        if (newData == null || newData.isMissingNode()) return;
        if (newData.has("username")) username = newData.get("username").asText(null);
        if (newData.has("nickname")) nickname = newData.get("nickname").asText(null);
        if (newData.has("userType")) userType = newData.get("userType").asText(null);
        if (newData.has("phone")) phone = newData.get("phone").asText(null);
        if (newData.has("email")) email = newData.get("email").asText(null);
        if (newData.has("avatarUrl")) avatarUrl = newData.get("avatarUrl").asText(null);
        if (newData.has("identity")) identity = newData.get("identity").asText(null);
    }

    public CasUserSession() {
        this.authTime = System.currentTimeMillis();
    }

    /**
     * 从 CAS Assertion 构建 CasUserSession
     *
     * <p>提取 Assertion 中的 Principal 和 Attributes，
     * 将 CAS 属性映射到 CasUserSession 的标准字段。</p>
     *
     * <p>多值属性（如 memberOf）保留原始类型（List），
     * 单值属性保留为 String，不做 toString() 转换。</p>
     *
     * @param assertion CAS Assertion（ST 验证成功后由 CAS Server 返回）
     * @return CasUserSession 实例
     */
    public static CasUserSession fromAssertion(Assertion assertion) {
        CasUserSession session = new CasUserSession();
        AttributePrincipal principal = assertion.getPrincipal();
        session.setUserId(principal.getName());

        // 合并 principal attributes 和 assertion attributes
        Map<String, Object> principalAttrs = principal.getAttributes();
        Map<String, Object> assertionAttrs = assertion.getAttributes();

        Map<String, Object> attrs = new HashMap<>();
        if (assertionAttrs != null) {
            attrs.putAll(assertionAttrs);
        }
        if (principalAttrs != null) {
            // principal attributes 覆盖 assertion attributes（principal 为权威源）
            attrs.putAll(principalAttrs);
        }

        session.setUsername(getStringAttr(attrs, "username"));
        session.setNickname(getStringAttr(attrs, "nickname"));
        session.setUserType(getStringAttr(attrs, "userType"));
        session.setPhone(getStringAttr(attrs, "phone"));
        session.setEmail(getStringAttr(attrs, "email"));
        session.setAvatarUrl(getStringAttr(attrs, "avatarUrl"));
        session.setIdentity(getStringAttr(attrs, "identity"));

        // 将所有 CAS 属性保留原始类型，多值属性（如 memberOf）保持为 List<String>
        Map<String, Object> preservedAttrs = new HashMap<>();
        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            if (entry.getValue() != null) {
                preservedAttrs.put(entry.getKey(), entry.getValue());
            }
        }
        session.setAttributes(preservedAttrs);

        return session;
    }

    /**
     * 从 CAS 属性中提取 memberOf 角色集合。
     * <p>
     * CAS 返回的 memberOf 可能是以下类型之一：
     * <ul>
     *   <li>{@code List<String>} — 多值属性的标准形式</li>
     *   <li>{@code String} — 单值情况</li>
     *   <li>{@code String} 格式为 {@code "[A, B]"} — toString() 残留，做兼容解析</li>
     * </ul>
     *
     * @return 角色集合，attributes 为空时返回空 Set
     */
    public Set<String> getMemberOf() {
        if (attributes == null) {
            return Collections.emptySet();
        }
        Object value = attributes.get("memberOf");
        if (value == null) {
            return Collections.emptySet();
        }

        // List<String> — 多值属性标准形式
        if (value instanceof List<?> list) {
            Set<String> roles = new HashSet<>();
            for (Object item : list) {
                if (item != null) {
                    roles.add(item.toString().trim());
                }
            }
            return roles;
        }

        // String — 单值或 toString() 残留
        if (value instanceof String str) {
            String cleaned = str.replaceAll("[\\[\\]]", "").trim();
            if (cleaned.isEmpty()) {
                return Collections.emptySet();
            }
            // 含逗号 → 多值
            if (cleaned.contains(",")) {
                Set<String> roles = new HashSet<>();
                for (String part : cleaned.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        roles.add(trimmed);
                    }
                }
                return roles;
            }
            return Set.of(cleaned);
        }

        return Collections.emptySet();
    }

    private static String getStringAttr(Map<String, Object> attrs, String key) {
        Object value = attrs.get(key);
        if (value == null) {
            return null;
        }
        // List 取第一个元素
        if (value instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            return first != null ? first.toString() : null;
        }
        return value.toString();
    }
}
