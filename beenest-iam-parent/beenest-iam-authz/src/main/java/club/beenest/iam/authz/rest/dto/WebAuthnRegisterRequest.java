package club.beenest.iam.authz.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 注册 Passkey 请求(浏览器 {@code navigator.credentials.create()} 的结果)。
 *
 * @param clientDataJSON    客户端数据(含挑战与来源,由协议库校验)
 * @param attestationObject 证明对象(CBOR,含公钥与认证器数据)
 * @param transports        认证器自报的传输方式;可为空
 * @param displayName       用户给这把密钥起的名字;为空时由服务端生成默认名
 */
public record WebAuthnRegisterRequest(

        @NotBlank(message = "缺少客户端数据")
        String clientDataJSON,

        @NotBlank(message = "缺少证明对象")
        String attestationObject,

        List<String> transports,

        @Size(max = 120, message = "名称过长")
        String displayName
) {
}
