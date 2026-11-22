package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 协议映射器响应。
 *
 * @param id            主键
 * @param clientId      客户端主键(为空表示域级)
 * @param name          映射器名称
 * @param claimName     目标 claim 名
 * @param claimType     claim 类型
 * @param userAttribute 用户属性键
 * @param claimTemplate 固定值模板
 * @param createdTime   创建时间
 */
public record ProtocolMapperResponse(
        Long id,
        Long clientId,
        String name,
        String claimName,
        String claimType,
        String userAttribute,
        String claimTemplate,
        LocalDateTime createdTime
) {
}
