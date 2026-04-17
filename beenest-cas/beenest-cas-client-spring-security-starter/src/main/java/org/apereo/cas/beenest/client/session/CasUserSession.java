package org.apereo.cas.beenest.client.session;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.apereo.cas.client.authentication.AttributePrincipal;
import org.apereo.cas.client.validation.Assertion;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * CAS 用户会话信息
 * <p>
 * 存储在 HttpSession 中，表示已通过 CAS 认证的用户。
 * 从 beenest-cas-client 迁移，保持字段完全兼容。
 */
@Data
public class CasUserSession implements Serializable {

    public static final String SESSION_KEY = "CAS_USER_SESSION";

    private static final long serialVersionUID = 1L;

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

    /** CAS 属性（ST 校验时返回的额外属性） */
    private Map<String, String> attributes;

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
     * @param assertion CAS Assertion（ST 验证成功后由 CAS Server 返回）
     * @return CasUserSession 实例
     */
    public static CasUserSession fromAssertion(Assertion assertion) {
        CasUserSession session = new CasUserSession();
        AttributePrincipal principal = assertion.getPrincipal();
        session.setUserId(principal.getName());

        // 从 CAS 属性中提取标准字段
        Map<String, Object> attrs = assertion.getAttributes();
        if (attrs == null) {
            attrs = Map.of();
        }

        session.setUsername(getStringAttr(attrs, "username"));
        session.setNickname(getStringAttr(attrs, "nickname"));
        session.setUserType(getStringAttr(attrs, "userType"));
        session.setPhone(getStringAttr(attrs, "phone"));
        session.setEmail(getStringAttr(attrs, "email"));
        session.setAvatarUrl(getStringAttr(attrs, "avatarUrl"));
        session.setIdentity(getStringAttr(attrs, "identity"));

        // 将所有 CAS 属性转为 String Map 保留原始信息
        Map<String, String> stringAttrs = new HashMap<>();
        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            if (entry.getValue() != null) {
                stringAttrs.put(entry.getKey(), entry.getValue().toString());
            }
        }
        session.setAttributes(stringAttrs);

        return session;
    }

    private static String getStringAttr(Map<String, Object> attrs, String key) {
        Object value = attrs.get(key);
        return value != null ? value.toString() : null;
    }
}
