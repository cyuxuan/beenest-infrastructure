package club.beenest.iam.authz.rest.dto;

import java.time.LocalDateTime;

/**
 * Passkey 凭证对外视图。
 * <p>不返回凭证公钥与用户句柄:列表页只需要辨认"这是哪台设备",
 * 公钥与句柄对前端没有任何用处,少暴露一个字段就少一条被拿去做关联分析的线索。</p>
 *
 * @param id            凭证主键
 * @param displayName   用户自定义名称
 * @param aaguid        认证器型号标识
 * @param transports    传输方式列表
 * @param backupEligible 是否可备份(同步型 Passkey)
 * @param backupState   当前是否已备份
 * @param lastUsedTime  最近使用时间;从未使用为 null
 * @param createdTime   绑定时间
 */
public record WebAuthnCredentialResponse(

        Long id,
        String displayName,
        String aaguid,
        java.util.List<String> transports,
        Boolean backupEligible,
        Boolean backupState,
        LocalDateTime lastUsedTime,
        LocalDateTime createdTime
) {
}
