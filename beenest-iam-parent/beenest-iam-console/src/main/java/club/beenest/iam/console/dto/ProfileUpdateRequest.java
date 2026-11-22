package club.beenest.iam.console.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 修改个人档案请求。
 * <p>登录名与用户类型由平台维护,自助端只能修改展示类信息与扩展属性。</p>
 *
 * @param nickname   昵称
 * @param email      邮箱
 * @param phone      手机号
 * @param attributes 扩展属性(整体覆盖)
 */
public record ProfileUpdateRequest(
        @Size(max = 100, message = "昵称长度不能超过 100")
        String nickname,

        @Email(message = "邮箱格式不正确")
        @Size(max = 200, message = "邮箱长度不能超过 200")
        String email,

        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        Map<String, String> attributes
) {
}
