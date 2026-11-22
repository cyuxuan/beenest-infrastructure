package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 更新身份域请求。
 * <p>域标识不可修改;字段为 null 表示不更新该项。</p>
 *
 * @param realmName      域名称
 * @param enabled        是否启用
 * @param accessTokenTtl  access token 有效期(秒)
 * @param refreshTokenTtl refresh token 有效期(秒)
 * @param ssoSessionIdle  会话空闲超时(秒)
 * @param ssoSessionMax   会话最大时长(秒)
 * @param subjectMode     本域默认的 OIDC sub 取值策略(客户端可单独覆盖)
 * @param theme           本域协议页主题目录名;空串表示改回跟随全局,null 表示不更新
 */
public record RealmUpdateRequest(
        @Size(max = 100, message = "域名称长度不能超过 100")
        String realmName,

        Boolean enabled,

        @Positive(message = "access token 有效期必须为正整数")
        Long accessTokenTtl,

        @Positive(message = "refresh token 有效期必须为正整数")
        Long refreshTokenTtl,

        @Positive(message = "会话空闲超时必须为正整数")
        Long ssoSessionIdle,

        @Positive(message = "会话最大时长必须为正整数")
        Long ssoSessionMax,

        @Pattern(regexp = "LOGIN_NAME|USER_ID", message = "主体标识策略只能是 LOGIN_NAME 或 USER_ID",
                flags = Pattern.Flag.CASE_INSENSITIVE)
        String subjectMode,

        @Pattern(regexp = "^$|^[a-zA-Z0-9_-]{1,64}$", message = "主题目录名只能包含字母、数字、下划线、中划线",
                flags = Pattern.Flag.CASE_INSENSITIVE)
        @Size(max = 64, message = "主题目录名长度不能超过 64")
        String theme
) {
}
