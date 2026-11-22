package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 域签名密钥响应。
 * <p>不返回公钥与私钥,仅返回类型、生效状态与启用/退役时间;密钥材料经 JWKS 端点发布。</p>
 *
 * @param id            主键
 * @param keyType       密钥类型(RSA)
 * @param active        是否当前生效
 * @param activatedTime 启用时间
 * @param retiredTime   退役时间(未退役为 null)
 */
public record RealmKeyResponse(
        Long id,
        String keyType,
        Boolean active,
        LocalDateTime activatedTime,
        LocalDateTime retiredTime
) {
}
