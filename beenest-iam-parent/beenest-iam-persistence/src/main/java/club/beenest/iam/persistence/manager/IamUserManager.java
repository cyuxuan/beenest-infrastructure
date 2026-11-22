package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.core.model.RequiredAction;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.converter.UserConverter;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.entity.UserCredential;
import club.beenest.iam.persistence.mapper.UserCredentialMapper;
import club.beenest.iam.persistence.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 用户存储实现(iam_user / iam_user_credential)。
 * <p>口令凭证与用户是两个单表,涉及两者的写入在同一事务内完成,
 * 避免出现"用户已建但无凭证"或"旧凭证已停用而新凭证未生效"的中间态。</p>
 */
@Component
public class IamUserManager implements UserProvider {

    /** 口令凭证类型 */
    private static final String CREDENTIAL_PASSWORD = "PASSWORD";

    private final UserMapper userMapper;
    private final UserCredentialMapper userCredentialMapper;
    private final UserConverter userConverter;
    private final PasswordEncoder passwordEncoder;
    /** 扩展属性序列化(iam_user.attributes 为 JSONB) */
    private final ObjectMapper objectMapper;

    public IamUserManager(UserMapper userMapper, UserCredentialMapper userCredentialMapper,
                          UserConverter userConverter, PasswordEncoder passwordEncoder,
                          ObjectMapper objectMapper) {
        this.userMapper = userMapper;
        this.userCredentialMapper = userCredentialMapper;
        this.userConverter = userConverter;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    // ===== 认证授权侧:领域模型读取 =====

    @Override
    public Optional<UserModel> findByUsername(Long realmId, String username) {
        return Optional.ofNullable(userConverter.toModel(
                userMapper.selectByRealmIdAndUsername(realmId, username)));
    }

    @Override
    public Optional<UserModel> findByPhone(Long realmId, String phone) {
        // 只认已验证手机号:未验证的号码可能是脏数据,不能当认证通道
        return Optional.ofNullable(userConverter.toModel(
                userMapper.selectByRealmIdAndPhone(realmId, phone)));
    }

    @Override
    public Optional<UserModel> findById(Long id) {
        return Optional.ofNullable(userConverter.toModel(userMapper.selectById(id)));
    }

    @Override
    public Optional<UserModel> findByEmail(Long realmId, String email) {
        return Optional.ofNullable(userConverter.toModel(
                userMapper.selectByRealmIdAndEmail(realmId, email)));
    }

    @Override
    public boolean verifyPassword(Long userId, String rawPassword) {
        // 1. 取用户当前启用的 PASSWORD 凭证
        UserCredential credential = userCredentialMapper.selectByUserIdAndType(userId, CREDENTIAL_PASSWORD);
        if (credential == null) {
            return false;
        }
        // 2. 已过期的凭证直接失败
        if (credential.getExpiredTime() != null
                && credential.getExpiredTime().isBefore(LocalDateTime.now())) {
            return false;
        }
        // 3. 校验密文
        return passwordEncoder.matches(rawPassword, credential.getSecretData());
    }

    @Override
    public boolean hasLocalPassword(Long userId) {
        return userCredentialMapper.selectByUserIdAndType(userId, CREDENTIAL_PASSWORD) != null;
    }

    @Override
    public void incrementTokenVersion(Long userId) {
        userMapper.incrementTokenVersion(userId);
    }

    @Override
    @Transactional
    public UserModel createFederatedUser(Long realmId, String username, String email, String nickname,
                                          String verifiedPhone, Map<String, String> attributes) {
        // 1. 组装用户实体:外部用户无密码凭证,状态正常
        User user = new User();
        user.setRealmId(realmId);
        user.setUsername(username);
        user.setEmail(email);
        user.setNickname(nickname);
        user.setStatus(1);
        user.setUserType("EXTERNAL");
        user.setEmailVerified(false);
        user.setTokenVersion(0);
        // 2. 提供方担保的手机号按已验证写入:唯一索引 (域, 已验证手机号) 据此约束
        if (verifiedPhone != null && !verifiedPhone.isBlank()) {
            user.setPhone(verifiedPhone);
            user.setPhoneVerified(true);
        }
        // 3. 扩展属性写入映射规则搬运过来的值(可空,空则写空对象)
        user.setAttributes(writeAttributes(attributes));
        // 4. 落库并回填主键
        userMapper.insert(user);
        return userConverter.toModel(user);
    }

    @Override
    public void updateVerifiedPhone(Long userId, String phone) {
        // 只更新手机号与验证标记,其余字段保持原值
        User update = new User();
        update.setId(userId);
        update.setPhone(phone);
        update.setPhoneVerified(true);
        userMapper.updateById(update);
    }

    /**
     * 序列化扩展属性为 JSON 字符串,空值写入空对象,列上不出现 null 字面量。
     *
     * @param attributes 扩展属性,可空
     * @return JSON 字符串
     * @throws IllegalStateException 序列化失败
     */
    private String writeAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (Exception e) {
            throw new IllegalStateException("扩展属性序列化失败", e);
        }
    }

    // ===== 管理侧:单表 CRUD =====

    /**
     * 按主键查询用户实体。
     *
     * @param id 用户主键
     * @return 用户,不存在时返回 null
     */
    public User getById(Long id) {
        return userMapper.selectById(id);
    }

    /**
     * 按主键批量查询用户实体,单次查询代替逐个回源。
     *
     * @param ids 用户主键集合
     * @return 用户列表;入参为空时返回空列表
     */
    public List<User> listByIds(List<Long> ids) {
        return (ids == null || ids.isEmpty()) ? List.of() : userMapper.selectByIds(ids);
    }

    /**
     * 按域与登录名查询实体,用于唯一性校验。
     *
     * @param realmId  域主键
     * @param username 登录名
     * @return 用户,不存在时返回 null
     */
    public User getByUsername(Long realmId, String username) {
        return userMapper.selectByRealmIdAndUsername(realmId, username);
    }

    /**
     * 按域与已验证手机号查询实体,用于已验证手机号的域内唯一性校验。
     *
     * @param realmId 域主键
     * @param phone   手机号
     * @return 用户,不存在时返回 null
     */
    public User getByPhone(Long realmId, String phone) {
        return userMapper.selectByRealmIdAndPhone(realmId, phone);
    }

    /**
     * 分页查询域内用户。
     *
     * @param realmId   域主键
     * @param condition 查询条件
     * @param pageNum   页码,从 1 开始
     * @param pageSize  每页条数
     * @return 分页结果
     */
    public PageResult<User> page(Long realmId, User condition, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<User> list = userMapper.selectByCondition(realmId, condition);
        PageInfo<User> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getPageNum(), pageInfo.getPageSize(),
                pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getList());
    }

    /**
     * 新建用户并写入口令凭证。
     *
     * @param user        用户实体,需已填 realmId 与 username
     * @param rawPassword 明文口令;为 null 或空白时只建用户不建凭证
     * @return 落库后的用户实体,主键已回填
     */
    @Transactional
    public User create(User user, String rawPassword) {
        // 1. 落库用户并回填主键
        userMapper.insert(user);
        // 2. 需要口令时同步写入 PASSWORD 凭证
        if (rawPassword != null && !rawPassword.isBlank()) {
            savePasswordCredential(user, rawPassword);
        }
        return user;
    }

    /**
     * 更新用户非空字段。
     *
     * @param user 用户实体
     * @return 影响行数
     */
    public int update(User user) {
        return userMapper.updateById(user);
    }

    /**
     * 批量更新用户状态(启用/禁用)。
     * <p>主键集合需调用方按域过滤后传入。</p>
     *
     * @param ids    用户主键集合
     * @param status 目标状态
     * @return 影响行数;入参为空时返回 0
     */
    public int updateStatusByIds(List<Long> ids, Integer status) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return userMapper.updateStatusByIds(ids, status);
    }

    /**
     * 逻辑删除用户,同时停用其全部口令凭证。
     *
     * @param id 用户主键
     * @return 影响行数
     */
    @Transactional
    public int remove(Long id) {
        userCredentialMapper.disableByUserIdAndType(id, CREDENTIAL_PASSWORD);
        return userMapper.softDeleteById(id);
    }

    /**
     * 重置口令:停用旧凭证后写入新凭证,并递增令牌版本使既有令牌全部失效。
     * <p>旧密文行保留不删除;凭证查询按 enabled 与 id 倒序取最新一条,新凭证立即生效。</p>
     *
     * @param userId      用户主键
     * @param rawPassword 新明文口令
     * @return 影响行数;用户不存在时返回 0
     */
    @Transactional
    public int resetPassword(Long userId, String rawPassword) {
        // 1. 用户必须存在
        User user = userMapper.selectById(userId);
        if (user == null) {
            return 0;
        }
        // 2. 停用旧凭证并写入新凭证
        userCredentialMapper.disableByUserIdAndType(userId, CREDENTIAL_PASSWORD);
        savePasswordCredential(user, rawPassword);
        // 3. 口令变更视为凭证泄露风险,令牌版本递增使既有令牌全部失效
        return userMapper.incrementTokenVersion(userId);
    }

    /**
     * 覆盖写入登录后待办事项。
     *
     * @param userId  用户主键
     * @param actions 待办集合;空集合写成空串,与列默认值一致
     * @return 影响行数
     */
    public int updateRequiredActions(Long userId, Set<RequiredAction> actions) {
        return userMapper.updateRequiredActions(userId, RequiredAction.join(actions));
    }

    /**
     * 标记用户邮箱已验证。
     *
     * @param userId 用户主键
     * @return 影响行数
     */
    public int markEmailVerified(Long userId) {
        return userMapper.markEmailVerified(userId);
    }

    /**
     * 写入一条 PASSWORD 凭证,密文由委托式编码器生成,自带算法前缀。
     *
     * @param user        用户实体,提供域与已回填的主键
     * @param rawPassword 明文口令
     */
    private void savePasswordCredential(User user, String rawPassword) {
        UserCredential credential = new UserCredential();
        credential.setRealmId(user.getRealmId());
        credential.setUserId(user.getId());
        credential.setCredentialType(CREDENTIAL_PASSWORD);
        credential.setSecretData(passwordEncoder.encode(rawPassword));
        credential.setAlgorithm("bcrypt");
        credential.setEnabled(true);
        userCredentialMapper.insert(credential);
    }
}
