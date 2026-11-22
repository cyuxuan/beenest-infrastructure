package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 新建身份域请求。
 * <p>域标识创建后不可修改,同时作为 issuer 路径段;超时参数为空时取数据库默认值。</p>
 *
 * @param realmKey       域标识(字母/数字/下划线/中划线)
 * @param realmName      域名称
 * @param enabled        是否启用,缺省启用
 * @param accessTokenTtl  access token 有效期(秒)
 * @param refreshTokenTtl refresh token 有效期(秒)
 * @param ssoSessionIdle  会话空闲超时(秒)
 * @param ssoSessionMax   会话最大时长(秒)
 * @param subjectMode     本域默认的 OIDC sub 取值策略,缺省 LOGIN_NAME
 * @param theme           本域协议页主题目录名(themes/ 下同名目录);缺省/NULL 跟随全局
 */
public record RealmCreateRequest(
        @NotBlank(message = "域标识不能为空")
        @Pattern(regexp = "^[a-zA-Z0-9_-]{2,64}$", message = "域标识只能包含字母、数字、下划线、中划线,长度 2-64")
        String realmKey,

        @NotBlank(message = "域名称不能为空")
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
