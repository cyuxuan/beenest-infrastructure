package club.beenest.iam.authz.rest.dto;

/**
 * MFA 质询响应:口令已通过,等待第二因子。
 *
 * @param mfaToken           一次性票据(提交动态口令时回传)
 * @param enrollmentRequired 是否要求先完成动态口令绑定(域策略强制 MFA 且未绑定)
 * @param realmKey           身份域标识(前端继续第二步时原样带回)
 */
public record MfaChallengeResponse(String mfaToken, boolean enrollmentRequired, String realmKey) {
}
