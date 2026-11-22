package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 更新用户请求。
 * <p>登录名与用户类型不可修改;字段为 null 表示不更新该项。
 * 口令变更走独立的重置口令接口。</p>
 *
 * @param nickname      昵称
 * @param email         邮箱
 * @param phone         手机号
 * @param status        状态:1 正常 / 0 禁用 / 2 锁定
 * @param emailVerified 邮箱是否已验证
 * @param phoneVerified 手机号是否已验证(仅已验证手机号可用于短信登录)
 * @param attributes    扩展属性(整体覆盖)
 */
public record UserUpdateRequest(
        @Size(max = 100, message = "昵称长度不能超过 100")
        String nickname,

        @Email(message = "邮箱格式不正确")
        @Size(max = 200, message = "邮箱长度不能超过 200")
        String email,

        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @Min(value = 0, message = "状态取值非法")
        @Max(value = 2, message = "状态取值非法")
        Integer status,

        Boolean emailVerified,

        Boolean phoneVerified,

        Map<String, String> attributes
) {
}
