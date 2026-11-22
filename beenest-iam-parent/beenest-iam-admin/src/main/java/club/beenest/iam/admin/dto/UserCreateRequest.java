package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 新建用户请求。
 * <p>口令明文仅在本次请求内使用,落库前即编码为密文;不传口令表示不创建密码凭证。</p>
 *
 * @param username      登录名(域内唯一)
 * @param nickname      昵称
 * @param email         邮箱
 * @param phone         手机号
 * @param userType      用户类型,缺省 INTERNAL
 * @param password      初始口令明文,可空
 * @param phoneVerified 手机号是否已验证(HR 导入场景可建号即标已验证)
 * @param attributes    扩展属性
 */
public record UserCreateRequest(
        @NotBlank(message = "登录名不能为空")
        @Pattern(regexp = "^[a-zA-Z0-9_.@-]{3,100}$", message = "登录名只能包含字母、数字、下划线、点、@、中划线,长度 3-100")
        String username,

        @Size(max = 100, message = "昵称长度不能超过 100")
        String nickname,

        @Email(message = "邮箱格式不正确")
        @Size(max = 200, message = "邮箱长度不能超过 200")
        String email,

        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        String userType,

        @Size(min = 8, max = 64, message = "口令长度需在 8-64 之间")
        String password,

        Boolean phoneVerified,

        Map<String, String> attributes
) {
}
