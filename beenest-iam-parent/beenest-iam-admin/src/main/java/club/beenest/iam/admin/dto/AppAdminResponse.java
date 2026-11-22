package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 应用管理员响应。
 *
 * @param id          管理员关系主键
 * @param appServiceId 应用主键
 * @param subjectType 主体类型:USER/GROUP
 * @param subjectId   主体主键
 * @param subjectName 主体展示名
 * @param subjectCode 主体编码(用户登录名/组编码)
 * @param grantedBy   授权人用户主键
 * @param createdTime 授权时间
 */
public record AppAdminResponse(
        Long id,
        Long appServiceId,
        String subjectType,
        Long subjectId,
        String subjectName,
        String subjectCode,
        Long grantedBy,
        LocalDateTime createdTime
) {
}
