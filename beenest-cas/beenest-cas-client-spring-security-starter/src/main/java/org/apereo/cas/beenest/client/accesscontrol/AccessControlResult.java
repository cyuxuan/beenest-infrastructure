package org.apereo.cas.beenest.client.accesscontrol;

/**
 * CAS 访问控制检查结果。
 *
 * @param granted 是否允许访问
 * @param userId  本地用户 ID（granted=true 时有值）
 * @param reason  拒绝原因（granted=false 时有值）
 */
public record AccessControlResult(boolean granted, String userId, String reason) {

    /**
     * 创建允许访问的结果。
     *
     * @param userId 本地用户 ID
     * @return 允许访问的结果
     */
    public static AccessControlResult granted(String userId) {
        return new AccessControlResult(true, userId, null);
    }

    /**
     * 创建拒绝访问的结果。
     *
     * @param reason 拒绝原因
     * @return 拒绝访问的结果
     */
    public static AccessControlResult denied(String reason) {
        return new AccessControlResult(false, null, reason);
    }
}