package club.beenest.iam.authz.mfa;

/**
 * MFA 待验证票据内容。
 *
 * @param userId    已通过第一因素认证的用户主键
 * @param loginType 第一因素认证方式(LoginMethod 存储码);可能为 null
 */
public record MfaPending(Long userId, String loginType) {
}
