package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 用户属性定义创建请求。
 *
 * @param attrKey         属性键(域内唯一,如 department)
 * @param attrName        属性显示名
 * @param attrType        属性类型:STRING/NUMBER/BOOLEAN/DATE/ENUM/JSON
 * @param required        是否必填
 * @param searchable      是否可搜索
 * @param uniqueFlag      是否全局唯一
 * @param enumOptions     枚举可选值(ENUM 类型,逗号分隔)
 * @param validationRegex 校验正则
 * @param description     说明
 */
public record AttributeDefCreateRequest(
        @NotBlank(message = "属性键不能为空") String attrKey,
        @NotBlank(message = "属性名称不能为空") String attrName,
        String attrType,
        Boolean required,
        Boolean searchable,
        Boolean uniqueFlag,
        String enumOptions,
        String validationRegex,
        String description
) {
}
