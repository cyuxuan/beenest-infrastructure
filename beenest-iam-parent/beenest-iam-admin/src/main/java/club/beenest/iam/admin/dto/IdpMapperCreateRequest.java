package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * IdP 属性映射器创建请求。
 *
 * @param mapperType      映射器类型(缺省 ATTRIBUTE)
 * @param sourceAttribute 外部属性名
 * @param targetAttribute 本地用户属性键
 */
public record IdpMapperCreateRequest(
        String mapperType,
        @NotBlank(message = "外部属性名不能为空") String sourceAttribute,
        @NotBlank(message = "本地属性键不能为空") String targetAttribute
) {
}
