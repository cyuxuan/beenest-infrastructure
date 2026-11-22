package club.beenest.iam.admin.converter;

import club.beenest.iam.admin.dto.UserCreateRequest;
import club.beenest.iam.admin.dto.UserResponse;
import club.beenest.iam.admin.dto.UserUpdateRequest;
import club.beenest.iam.core.model.RequiredAction;
import club.beenest.iam.persistence.converter.UserConverter;
import club.beenest.iam.persistence.entity.User;
import org.springframework.stereotype.Component;

/**
 * 用户管理端对象转换器。
 * <p>扩展属性在实体侧是 JSONB 文本,在接口侧是 Map,转换统一委托给持久层的
 * {@link UserConverter},保证与认证侧的解析规则一致。</p>
 */
@Component
public class UserDtoConverter {

    /** 用户类型:内部用户 */
    private static final String TYPE_INTERNAL = "INTERNAL";
    /** 用户状态:正常 */
    private static final int STATUS_NORMAL = 1;

    private final UserConverter userConverter;

    public UserDtoConverter(UserConverter userConverter) {
        this.userConverter = userConverter;
    }

    /**
     * 用户实体转响应,扩展属性由 JSONB 文本解析为 Map。
     *
     * @param user 用户实体,可为 null
     * @return 用户响应,入参为 null 时返回 null
     */
    public UserResponse toResponse(User user) {
        if (user == null) {
            return null;
        }
        return new UserResponse(
                user.getId(),
                user.getRealmId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getPhone(),
                user.getStatus(),
                user.getUserType(),
                user.getEmailVerified(),
                user.getPhoneVerified(),
                user.getTokenVersion(),
                userConverter.parseAttributes(user.getAttributes()),
                user.getCreatedTime(),
                user.getUpdatedTime(),
                RequiredAction.parse(user.getRequiredActions()).stream()
                        .map(Enum::name)
                        .toList());
    }

    /**
     * 新建请求转实体。
     * <p>用户类型缺省为内部用户,状态固定为正常,令牌版本从 0 起;
     * 手机号验证态默认 false,仅显式声明时置为已验证。</p>
     *
     * @param request 新建请求
     * @param realmId 身份域主键
     * @return 用户实体
     */
    public User toEntity(UserCreateRequest request, Long realmId) {
        User user = new User();
        user.setRealmId(realmId);
        user.setUsername(request.username());
        user.setNickname(request.nickname());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        user.setUserType(request.userType() == null || request.userType().isBlank()
                ? TYPE_INTERNAL : request.userType());
        user.setStatus(STATUS_NORMAL);
        user.setEmailVerified(false);
        // 手机号验证态默认未验证,仅显式声明时置为已验证(HR 批量导入场景)
        user.setPhoneVerified(Boolean.TRUE.equals(request.phoneVerified()));
        user.setTokenVersion(0);
        user.setAttributes(userConverter.toAttributeJson(request.attributes()));
        return user;
    }

    /**
     * 将更新请求中的非空字段写入实体,登录名与用户类型不可修改。
     *
     * @param user    库中用户实体
     * @param request 更新请求
     */
    public void applyUpdate(User user, UserUpdateRequest request) {
        if (request.nickname() != null) {
            user.setNickname(request.nickname());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone());
        }
        if (request.status() != null) {
            user.setStatus(request.status());
        }
        if (request.emailVerified() != null) {
            user.setEmailVerified(request.emailVerified());
        }
        if (request.phoneVerified() != null) {
            user.setPhoneVerified(request.phoneVerified());
        }
        if (request.attributes() != null) {
            user.setAttributes(userConverter.toAttributeJson(request.attributes()));
        }
    }
}
