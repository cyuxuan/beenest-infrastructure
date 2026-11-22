package club.beenest.iam.core.spi.federation;

import java.util.Map;

/**
 * 授权跳转请求。
 *
 * @param redirectUri 回调地址(本 IAM 的 broker 回调端点)
 * @param state       防伪状态,回调时原样带回校验
 * @param extraParams 附加查询参数,由提供方决定内容
 */
public record AuthorizationRequest(
        String redirectUri,
        String state,
        Map<String, String> extraParams
) {
}
