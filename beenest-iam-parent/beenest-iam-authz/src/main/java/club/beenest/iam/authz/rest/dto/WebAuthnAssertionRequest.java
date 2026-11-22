package club.beenest.iam.authz.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Passkey 断言请求(浏览器 {@code navigator.credentials.get()} 的结果)。
 *
 * @param challenge         本次挑战(原样回传,服务端据此找回预期值)
 * @param id                凭证标识(与 rawId 同值,保留以贴合浏览器结构)
 * @param rawId             凭证标识的 Base64URL
 * @param clientDataJSON    客户端数据
 * @param authenticatorData 认证器数据(含签名计数器与标志位)
 * @param signature         签名
 * @param userHandle        用户句柄;无用户名登录时由认证器给出
 */
public record WebAuthnAssertionRequest(

        @NotBlank(message = "缺少挑战")
        String challenge,

        String id,

        @NotBlank(message = "缺少凭证标识")
        String rawId,

        @NotBlank(message = "缺少客户端数据")
        String clientDataJSON,

        @NotBlank(message = "缺少认证器数据")
        String authenticatorData,

        @NotBlank(message = "缺少签名")
        String signature,

        String userHandle
) {
}
