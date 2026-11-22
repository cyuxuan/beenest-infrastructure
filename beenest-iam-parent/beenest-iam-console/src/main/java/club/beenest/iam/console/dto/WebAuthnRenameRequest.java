package club.beenest.iam.console.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Passkey 重命名请求。
 *
 * @param displayName 新名称
 */
public record WebAuthnRenameRequest(

        @NotBlank(message = "请输入名称")
        @Size(max = 120, message = "名称过长")
        String displayName
) {
}
