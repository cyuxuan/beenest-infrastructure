package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * IdP 属性映射器响应。
 *
 * @param id              主键
 * @param mapperType      映射器类型
 * @param sourceAttribute 外部属性名
 * @param targetAttribute 本地用户属性键
 * @param createdTime     创建时间
 */
public record IdpMapperResponse(
        Long id,
        String mapperType,
        String sourceAttribute,
        String targetAttribute,
        LocalDateTime createdTime
) {
}
