package club.beenest.iam.authz.rest.dto;

import jakarta.validation.constraints.Size;

/**
 * Passkey 登录选项请求。
 *
 * @param account 登录名;为空表示无用户名登录(由认证器自行选择密钥)
 */
public record WebAuthnLoginOptionsRequest(

        @Size(max = 200, message = "账号过长")
        String account
) {
}
