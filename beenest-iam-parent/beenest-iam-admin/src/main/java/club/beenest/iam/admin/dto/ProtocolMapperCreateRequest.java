package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 协议映射器创建请求。
 * <p>userAttribute 与 claimTemplate 二选一:前者映射用户属性,后者输出固定值。</p>
 *
 * @param clientId      客户端主键(为空表示域级映射,对全部客户端生效)
 * @param name          映射器名称
 * @param claimName     目标 claim 名
 * @param claimType     claim 类型:STRING/NUMBER/BOOLEAN/JSON
 * @param userAttribute 用户属性键
 * @param claimTemplate 固定值模板
 */
public record ProtocolMapperCreateRequest(
        Long clientId,
        @NotBlank(message = "映射器名称不能为空") String name,
        @NotBlank(message = "claim 名不能为空") String claimName,
        String claimType,
        String userAttribute,
        String claimTemplate
) {
}
