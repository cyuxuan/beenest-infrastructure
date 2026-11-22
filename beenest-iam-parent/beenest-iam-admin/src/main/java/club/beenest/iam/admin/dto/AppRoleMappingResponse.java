package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 应用角色映射响应。
 *
 * @param id             映射主键
 * @param appServiceId   应用主键
 * @param sourceType     来源类型:GROUP/REALM_ROLE
 * @param sourceId       来源主体主键
 * @param sourceName     来源展示名
 * @param sourceCode     来源编码(组编码/角色编码)
 * @param targetRoleCode 目标应用角色码
 * @param enabled        是否启用
 * @param createdTime    创建时间
 */
public record AppRoleMappingResponse(
        Long id,
        Long appServiceId,
        String sourceType,
        Long sourceId,
        String sourceName,
        String sourceCode,
        String targetRoleCode,
        Boolean enabled,
        LocalDateTime createdTime
) {
}
