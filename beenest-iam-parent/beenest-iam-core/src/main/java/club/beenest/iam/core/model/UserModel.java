package club.beenest.iam.core.model;

import java.util.Map;
import java.util.Set;

/**
 * 用户领域模型。
 * <p>认证流程使用的不可变快照;递增 {@code tokenVersion} 可使该用户已签发的全部令牌失效。</p>
 *
 * @param id              主键
 * @param realmId         所属身份域
 * @param username        登录名
 * @param nickname        昵称
 * @param email           邮箱
 * @param phone           手机号
 * @param status          状态:1 正常 / 0 禁用 / 2 锁定
 * @param userType        用户类型:INTERNAL / EXTERNAL / SERVICE
 * @param emailVerified   邮箱是否已验证
 * @param tokenVersion    令牌版本(全局下线即递增)
 * @param attributes      扩展属性(来自 iam_user.attributes JSONB)
 * @param requiredActions 登录后必须完成的事项,为空表示无待办
 */
public record UserModel(
        Long id,
        Long realmId,
        String username,
        String nickname,
        String email,
        String phone,
        int status,
        String userType,
        boolean emailVerified,
        int tokenVersion,
        Map<String, String> attributes,
        Set<RequiredAction> requiredActions
) {

    /** 构造不带待办事项的用户快照。 */
    public UserModel(Long id, Long realmId, String username, String nickname, String email, String phone,
                     int status, String userType, boolean emailVerified, int tokenVersion,
                     Map<String, String> attributes) {
        this(id, realmId, username, nickname, email, phone, status, userType,
                emailVerified, tokenVersion, attributes, Set.of());
    }

    /**
     * 是否有待办事项。
     *
     * @return requiredActions 非空时为 true
     */
    public boolean hasRequiredActions() {
        return requiredActions != null && !requiredActions.isEmpty();
    }
}
