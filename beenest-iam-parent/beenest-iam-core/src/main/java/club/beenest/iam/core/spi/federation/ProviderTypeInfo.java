package club.beenest.iam.core.spi.federation;

import java.util.List;
import java.util.Set;

/**
 * 提供方类型元数据。
 * <p>描述该类型提供方需要的配置项、支持的登录形态与自定义授权类型,
 * 供管理端渲染配置表单、登录页判断是否渲染跳转入口。</p>
 *
 * @param providerId       提供方类型标识
 * @param name             展示名
 * @param loginModes       该类型支持的登录形态
 * @param grantType        该类型在标准令牌端点上的自定义授权类型(无则为空)
 * @param configProperties 该类型支持的配置项声明
 */
public record ProviderTypeInfo(
        String providerId,
        String name,
        Set<ProviderLoginMode> loginModes,
        String grantType,
        List<ProviderConfigProperty> configProperties
) {
}
