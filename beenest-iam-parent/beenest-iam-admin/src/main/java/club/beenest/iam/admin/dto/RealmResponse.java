package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 身份域响应。
 *
 * @param id             主键
 * @param realmKey       域标识
 * @param realmName      域名称
 * @param enabled        是否启用
 * @param defaultRealm   是否默认域(默认域不可删除)
 * @param accessTokenTtl  access token 有效期(秒)
 * @param refreshTokenTtl refresh token 有效期(秒)
 * @param ssoSessionIdle  会话空闲超时(秒)
 * @param ssoSessionMax   会话最大时长(秒)
 * @param subjectMode     本域默认的 OIDC sub 取值策略
 * @param theme           本域协议页主题目录名(为空表示跟随全局)
 * @param createdTime    创建时间
 * @param updatedTime    更新时间
 */
public record RealmResponse(
        Long id,
        String realmKey,
        String realmName,
        Boolean enabled,
        Boolean defaultRealm,
        Long accessTokenTtl,
        Long refreshTokenTtl,
        Long ssoSessionIdle,
        Long ssoSessionMax,
        String subjectMode,
        String theme,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
}
