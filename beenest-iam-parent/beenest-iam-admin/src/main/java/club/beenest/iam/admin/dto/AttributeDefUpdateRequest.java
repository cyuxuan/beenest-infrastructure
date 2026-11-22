package club.beenest.iam.admin.dto;

/**
 * 用户属性定义更新请求(attrKey 不可变)。
 *
 * @param attrName        属性显示名
 * @param attrType        属性类型
 * @param required        是否必填
 * @param searchable      是否可搜索
 * @param uniqueFlag      是否全局唯一
 * @param enumOptions     枚举可选值
 * @param validationRegex 校验正则
 * @param description     说明
 */
public record AttributeDefUpdateRequest(
        String attrName,
        String attrType,
        Boolean required,
        Boolean searchable,
        Boolean uniqueFlag,
        String enumOptions,
        String validationRegex,
        String description
) {
}
