package club.beenest.iam.console.dto;

import java.util.Map;

/**
 * 个人档案响应。
 *
 * @param userId        用户主键
 * @param username      登录名
 * @param nickname      昵称
 * @param email         邮箱
 * @param phone         手机号
 * @param emailVerified 邮箱是否已验证
 * @param attributes    扩展属性
 */
public record ConsoleProfileResponse(
        Long userId,
        String username,
        String nickname,
        String email,
        String phone,
        Boolean emailVerified,
        Map<String, String> attributes
) {
}
