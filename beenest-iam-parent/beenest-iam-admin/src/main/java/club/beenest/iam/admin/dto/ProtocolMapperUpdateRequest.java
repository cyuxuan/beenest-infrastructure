package club.beenest.iam.admin.dto;

/**
 * 协议映射器更新请求。
 *
 * @param name          映射器名称
 * @param claimName     目标 claim 名
 * @param claimType     claim 类型
 * @param userAttribute 用户属性键
 * @param claimTemplate 固定值模板
 */
public record ProtocolMapperUpdateRequest(
        String name,
        String claimName,
        String claimType,
        String userAttribute,
        String claimTemplate
) {
}
