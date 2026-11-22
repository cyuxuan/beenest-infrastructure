package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 变更客户端类型请求。
 * <p>类型只能在客户端尚未投入使用时变更:切换会改变令牌端点的认证方式,
 * 已签发的授权若跨类型继续可用,机密性就取决于配置历史而不是当下配置。</p>
 *
 * @param clientType 目标类型:PUBLIC / CONFIDENTIAL
 */
public record ClientTypeChangeRequest(
        @NotBlank(message = "客户端类型不能为空")
        @Pattern(regexp = "PUBLIC|CONFIDENTIAL", message = "客户端类型只能是 PUBLIC 或 CONFIDENTIAL")
        String clientType
) {
}
