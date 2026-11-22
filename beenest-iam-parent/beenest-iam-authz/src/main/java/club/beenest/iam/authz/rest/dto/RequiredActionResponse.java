package club.beenest.iam.authz.rest.dto;

/**
 * 账户待办事项。
 *
 * @param code 动作代码(UPDATE_PASSWORD / VERIFY_EMAIL / CONFIGURE_TOTP)
 * @param name 展示名
 */
public record RequiredActionResponse(String code, String name) {
}
