package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户响应。
 *
 * @param id            主键
 * @param realmId       所属域
 * @param username      登录名
 * @param nickname      昵称
 * @param email         邮箱
 * @param phone         手机号
 * @param status        状态:1 正常 / 0 禁用 / 2 锁定
 * @param userType      用户类型
 * @param emailVerified 邮箱是否已验证
 * @param phoneVerified 手机号是否已验证(仅已验证手机号可用于短信登录)
 * @param tokenVersion  令牌版本,自增即表示全端下线
 * @param attributes    扩展属性
 * @param createdTime   创建时间
 * @param updatedTime   更新时间
 * @param requiredActions 登录后必须完成的事项;空列表表示无待办
 */
public record UserResponse(
        Long id,
        Long realmId,
        String username,
        String nickname,
        String email,
        String phone,
        Integer status,
        String userType,
        Boolean emailVerified,
        Boolean phoneVerified,
        Integer tokenVersion,
        Map<String, String> attributes,
        LocalDateTime createdTime,
        LocalDateTime updatedTime,
        java.util.List<String> requiredActions
) {
}
