package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.common.exception.BusinessException;
import org.apereo.cas.beenest.common.util.UserTypeUtils;
import org.apereo.cas.beenest.dto.UserRegisterDTO;
import org.apereo.cas.beenest.dto.UserUpdateDTO;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.UUID;

/**
 * 用户管理服务
 * <p>
 * 提供用户 CRUD、启禁用、账号锁定等管理能力。
 */
@Slf4j
@RequiredArgsConstructor
public class UserAdminService {

    private final UnifiedUserMapper userMapper;
    private final UserSyncService userSyncService;
    private final AppAccessService appAccessService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 注册新用户（管理端调用）
     *
     * @param dto       注册请求
     * @return 注册后的用户
     */
    public UnifiedUserDO registerUser(UserRegisterDTO dto) {
        // 1. 唯一性校验
        if (StringUtils.isNotBlank(dto.getUsername()) && userMapper.selectByUsername(dto.getUsername()) != null) {
            throw new BusinessException(400, "用户名已存在");
        }
        if (StringUtils.isNotBlank(dto.getPhone()) && userMapper.selectByPhone(dto.getPhone()) != null) {
            throw new BusinessException(400, "手机号已注册");
        }
        if (StringUtils.isNotBlank(dto.getEmail()) && userMapper.selectByEmail(dto.getEmail()) != null) {
            throw new BusinessException(400, "邮箱已注册");
        }

        // 2. 构建用户实体
        UnifiedUserDO user = new UnifiedUserDO();
        user.setUserId("U" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        user.setUsername(dto.getUsername());
        user.setNickname(dto.getNickname());
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        user.setUserType(UserTypeUtils.normalize(dto.getUserType()));
        user.setLoginType("USERNAME_PASSWORD");
        user.setStatus(1);
        user.setFailedLoginCount(0);
        user.setTokenVersion(1);

        userMapper.insert(user);

        // 3. 自动赋权
        if (dto.getServiceId() != null) {
            appAccessService.autoGrantOnRegister(user.getUserId(), dto.getServiceId());
        }

        // 4. 记录变更
        userSyncService.recordChange(user.getUserId(), "CREATE", null, user);

        LOGGER.info("注册用户: userId={}, serviceId={}", user.getUserId(), dto.getServiceId());
        return user;
    }

    /**
     * 更新用户信息（只允许更新安全字段）
     */
    public void updateUser(String userId, UserUpdateDTO dto) {
        UnifiedUserDO oldUser = userMapper.selectByUserId(userId);
        if (oldUser == null) {
            throw new BusinessException(404, "用户不存在: " + userId);
        }

        // 构建更新实体（只包含允许修改的字段）
        UnifiedUserDO updateEntity = new UnifiedUserDO();
        updateEntity.setUserId(userId);
        updateEntity.setNickname(dto.getNickname());
        updateEntity.setPhone(dto.getPhone());
        updateEntity.setEmail(dto.getEmail());
        updateEntity.setAvatarUrl(dto.getAvatarUrl());
        updateEntity.setUserType(UserTypeUtils.normalize(dto.getUserType()));

        userMapper.updateByUserId(updateEntity);
        userSyncService.recordChange(userId, "UPDATE", oldUser, updateEntity);
        LOGGER.info("更新用户: userId={}", userId);
    }

    /**
     * 删除用户（软删除）
     */
    public void deleteUser(String userId) {
        UnifiedUserDO oldUser = userMapper.selectByUserId(userId);
        if (oldUser == null) {
            throw new BusinessException(404, "用户不存在: " + userId);
        }
        userMapper.updateStatus(userId, 4);
        userSyncService.recordChange(userId, "DELETE", oldUser, null);
        LOGGER.info("删除用户: userId={}", userId);
    }

    /**
     * 启用/禁用用户
     */
    public void updateStatus(String userId, int status) {
        UnifiedUserDO oldUser = userMapper.selectByUserId(userId);
        if (oldUser == null) {
            throw new BusinessException(404, "用户不存在: " + userId);
        }
        userMapper.updateStatus(userId, status);
        userSyncService.recordChange(userId, "STATUS_CHANGE", oldUser.getStatus(), status);
        LOGGER.info("更新用户状态: userId={}, status={}", userId, status);
    }

    /**
     * 获取用户信息
     */
    public UnifiedUserDO getUser(String userId) {
        return userMapper.selectByUserId(userId);
    }

    /**
     * 批量查询用户（SQL IN 方式，避免 N+1）
     */
    public List<UnifiedUserDO> batchGetUsers(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userMapper.selectByUserIds(userIds);
    }
}
