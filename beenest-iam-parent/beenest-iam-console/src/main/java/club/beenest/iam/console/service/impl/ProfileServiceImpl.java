package club.beenest.iam.console.service.impl;

import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.security.PasswordChangeService;
import club.beenest.iam.authz.token.TokenStateCache;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.console.dto.ChangePasswordRequest;
import club.beenest.iam.console.dto.ConsoleProfileResponse;
import club.beenest.iam.console.dto.ProfileUpdateRequest;
import club.beenest.iam.console.service.ProfileService;
import club.beenest.iam.persistence.converter.UserConverter;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.springframework.stereotype.Service;

/**
 * 个人档案服务实现。
 */
@Service
public class ProfileServiceImpl implements ProfileService {

    private final IamUserManager userManager;
    private final UserConverter userConverter;
    private final TokenStateCache tokenStateCache;
    private final PasswordChangeService passwordChangeService;

    public ProfileServiceImpl(IamUserManager userManager, UserConverter userConverter,
                              TokenStateCache tokenStateCache, PasswordChangeService passwordChangeService) {
        this.userManager = userManager;
        this.userConverter = userConverter;
        this.tokenStateCache = tokenStateCache;
        this.passwordChangeService = passwordChangeService;
    }

    @Override
    public ConsoleProfileResponse get() {
        return toResponse(requireSelf());
    }

    @Override
    public ConsoleProfileResponse update(ProfileUpdateRequest request) {
        // 1. 只读取本人记录
        User user = requireSelf();
        // 2. 自助端可改展示类信息与扩展属性,登录名与状态由管理端维护
        if (request.nickname() != null) {
            user.setNickname(request.nickname());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
            // 邮箱变更后原验证结论不再成立,需重新验证
            user.setEmailVerified(false);
        }
        if (request.phone() != null) {
            user.setPhone(request.phone());
        }
        if (request.attributes() != null) {
            user.setAttributes(userConverter.toAttributeJson(request.attributes()));
        }
        // 3. 落库并回读
        userManager.update(user);
        return toResponse(userManager.getById(user.getId()));
    }

    @Override
    public void changePassword(ChangePasswordRequest request) {
        // 1. 只允许修改本人
        IamUserPrincipal principal = CurrentUserHolder.require();
        // 2. 原口令校验:会话被接管时也无法在不知道旧口令的情况下改密
        if (!userManager.verifyPassword(principal.getUserId(), request.oldPassword())) {
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS, "原口令不正确");
        }
        // 3. 新旧口令不得相同,避免"改密"操作实际什么都没变
        if (request.oldPassword().equals(request.newPassword())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "新口令不能与原口令相同");
        }
        // 4. 按域策略改密:复杂度/历史校验 + 写入新凭证 + 记录历史 + 令牌版本递增
        passwordChangeService.changePassword(principal.getRealmId(), principal.getUserId(), request.newPassword());
        // 5. 令牌状态缓存失效,验签立即拒绝旧令牌
        tokenStateCache.invalidate(principal.getUserId());
    }

    /**
     * 读取当前登录用户自己的记录。
     *
     * @return 当前用户的档案实体
     * @throws BusinessException 用户记录不存在
     */
    private User requireSelf() {
        Long userId = CurrentUserHolder.require().getUserId();
        User user = userManager.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: " + userId);
        }
        return user;
    }

    /**
     * 档案实体转响应视图。
     *
     * @param user 档案实体
     * @return 档案响应
     */
    private ConsoleProfileResponse toResponse(User user) {
        return new ConsoleProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getPhone(),
                user.getEmailVerified(),
                userConverter.parseAttributes(user.getAttributes()));
    }
}
