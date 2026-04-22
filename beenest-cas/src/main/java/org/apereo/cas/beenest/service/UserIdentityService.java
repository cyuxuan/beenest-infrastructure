package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.common.exception.BusinessException;
import org.apereo.cas.beenest.common.util.UserTypeUtils;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;

import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 多渠道用户身份管理服务
 * <p>
 * 统一管理微信/抖音/支付宝/手机号等多种渠道的用户查找、注册、账号合并。
 * <p>
 * 账号合并规则（优先级从高到低）：
 * 1. unionid 匹配（微信生态跨应用）
 * 2. 渠道 openid 精确匹配
 * 3. 手机号匹配（需 status != DELETED）
 * 4. 不匹配则创建新用户
 * <p>
 * 关键设计：
 * - 手机号合并后自动补充当前渠道 openid 到已有用户，防止下次登录无法找到
 * - 使用 @Transactional + 数据库 UNIQUE 约束防止并发重复注册
 * - 合并目标账号如果是锁定状态则拒绝合并
 */
@Slf4j
@RequiredArgsConstructor
@Transactional(transactionManager = "beenestTransactionManager")
public class UserIdentityService {

    private static final java.util.Set<String> INVALID_NICKNAMES = java.util.Set.of(
            "未登录", "null", "undefined", "nil", "游客", "微信用户", "抖音用户", "支付宝用户"
    );

    /**
     * 用户查找或注册结果。
     *
     * @param user       统一用户
     * @param firstLogin  是否首次创建用户
     */
    public record UserIdentityResult(UnifiedUserDO user, boolean firstLogin) {
    }

    private final UnifiedUserMapper userMapper;
    private final UserSyncService userSyncService;
    private final AppAccessService appAccessService;
    private final String autoGrantServiceIds;
    /**
     * 微信渠道：查找或注册用户
     */
    public UnifiedUserDO findOrRegisterByWechat(String openid, String unionid,
                                                 String phone, String userType, String nickname) {
        return findOrRegisterByWechatResult(openid, unionid, phone, userType, nickname).user();
    }

    /**
     * 微信渠道：查找或注册用户，并返回是否首次注册。
     */
    public UserIdentityResult findOrRegisterByWechatResult(String openid, String unionid,
                                                           String phone, String userType, String nickname) {
        String sanitizedNickname = sanitizeNickname(nickname);

        // 1. 查找用户（优先级：unionid > openid）
        UnifiedUserDO user = null;
        boolean firstLogin = false;
        if (StringUtils.isNotBlank(unionid)) {
            user = userMapper.selectByUnionid(unionid);
        }
        if (user == null && StringUtils.isNotBlank(openid)) {
            user = userMapper.selectByOpenid(openid, "WECHAT");
        }

        // 2. 手机号合并（合并后补充当前渠道 openid）
        if (user == null && StringUtils.isNotBlank(phone)) {
            user = tryMergeByPhone(phone);
            if (user != null) {
                boolean updated = false;
                if (StringUtils.isNotBlank(openid) && StringUtils.isBlank(user.getOpenid())) {
                    user.setOpenid(openid);
                    updated = true;
                }
                if (StringUtils.isNotBlank(unionid) && StringUtils.isBlank(user.getUnionid())) {
                    user.setUnionid(unionid);
                    updated = true;
                }
                if (updated) {
                    userMapper.updateByUserId(user);
                    LOGGER.info("合并微信渠道标识: userId={}", user.getUserId());
                }
            }
        }

        // 3. 自动注册（带并发保护）
        if (user == null) {
            user = createWechatUser(openid, unionid, phone, userType, sanitizedNickname);
            userSyncService.recordChange(user.getUserId(), "CREATE", null, user);
            autoGrantDefaultServices(user.getUserId());
            firstLogin = true;
            LOGGER.info("微信自动注册: userId={}", user.getUserId());
        } else {
            boolean updated = syncWechatIdentity(user, openid, unionid);
            if (tryRefreshNickname(user, sanitizedNickname)) {
                updated = true;
            }
            if (updated) {
                userMapper.updateByUserId(user);
            }
        }

        return new UserIdentityResult(reloadUser(user), firstLogin);
    }

    /**
     * 抖音渠道：查找或注册用户
     */
    public UnifiedUserDO findOrRegisterByDouyin(String openid, String unionid,
                                                 String phone, String userType, String nickname) {
        return findOrRegisterByDouyinResult(openid, unionid, phone, userType, nickname).user();
    }

    /**
     * 抖音渠道：查找或注册用户，并返回是否首次注册。
     */
    public UserIdentityResult findOrRegisterByDouyinResult(String openid, String unionid,
                                                           String phone, String userType, String nickname) {
        String sanitizedNickname = sanitizeNickname(nickname);

        UnifiedUserDO user = null;
        boolean firstLogin = false;
        if (StringUtils.isNotBlank(unionid)) {
            user = userMapper.selectByDouyinUnionid(unionid);
        }
        if (user == null && StringUtils.isNotBlank(openid)) {
            user = userMapper.selectByDouyinOpenid(openid);
        }
        // 手机号合并 + 补充抖音渠道标识
        if (user == null && StringUtils.isNotBlank(phone)) {
            user = tryMergeByPhone(phone);
            if (user != null) {
                boolean updated = false;
                if (StringUtils.isNotBlank(openid) && StringUtils.isBlank(user.getDouyinOpenid())) {
                    user.setDouyinOpenid(openid);
                    updated = true;
                }
                if (StringUtils.isNotBlank(unionid) && StringUtils.isBlank(user.getDouyinUnionid())) {
                    user.setDouyinUnionid(unionid);
                    updated = true;
                }
                if (updated) {
                    userMapper.updateByUserId(user);
                    LOGGER.info("合并抖音渠道标识: userId={}", user.getUserId());
                }
            }
        }
        if (user == null) {
            user = createDouyinUser(openid, unionid, userType, sanitizedNickname);
            userSyncService.recordChange(user.getUserId(), "CREATE", null, user);
            autoGrantDefaultServices(user.getUserId());
            firstLogin = true;
            LOGGER.info("抖音自动注册: userId={}", user.getUserId());
        } else if (tryRefreshNickname(user, sanitizedNickname)) {
            userMapper.updateByUserId(user);
        }
        return new UserIdentityResult(reloadUser(user), firstLogin);
    }

    /**
     * 支付宝渠道：查找或注册用户
     */
    public UnifiedUserDO findOrRegisterByAlipay(String alipayUid, String phone,
                                                 String userType, String nickname) {
        return findOrRegisterByAlipayResult(alipayUid, phone, userType, nickname).user();
    }

    /**
     * 支付宝渠道：查找或注册用户，并返回是否首次注册。
     */
    public UserIdentityResult findOrRegisterByAlipayResult(String alipayUid, String phone,
                                                           String userType, String nickname) {
        String sanitizedNickname = sanitizeNickname(nickname);

        UnifiedUserDO user = null;
        boolean firstLogin = false;
        if (StringUtils.isNotBlank(alipayUid)) {
            user = userMapper.selectByAlipayUid(alipayUid);
        }
        // 手机号合并 + 补充支付宝渠道标识
        if (user == null && StringUtils.isNotBlank(phone)) {
            user = tryMergeByPhone(phone);
            if (user != null) {
                if (StringUtils.isNotBlank(alipayUid) && StringUtils.isBlank(user.getAlipayUid())) {
                    user.setAlipayUid(alipayUid);
                    userMapper.updateByUserId(user);
                    LOGGER.info("合并支付宝渠道标识: userId={}", user.getUserId());
                }
            }
        }
        if (user == null) {
            user = createAlipayUser(alipayUid, userType, sanitizedNickname);
            userSyncService.recordChange(user.getUserId(), "CREATE", null, user);
            autoGrantDefaultServices(user.getUserId());
            firstLogin = true;
            LOGGER.info("支付宝自动注册: userId={}", user.getUserId());
        } else if (tryRefreshNickname(user, sanitizedNickname)) {
            userMapper.updateByUserId(user);
        }
        return new UserIdentityResult(reloadUser(user), firstLogin);
    }

    /**
     * 手机号渠道：查找或注册用户
     */
    public UnifiedUserDO findOrRegisterByPhone(String phone, String userType) {
        return findOrRegisterByPhoneResult(phone, userType).user();
    }

    /**
     * 手机号渠道：查找或注册用户，并返回是否首次注册。
     */
    public UserIdentityResult findOrRegisterByPhoneResult(String phone, String userType) {
        UnifiedUserDO user = userMapper.selectByPhone(phone);
        boolean firstLogin = false;
        if (user == null) {
            user = createPhoneUser(phone, userType);
            userSyncService.recordChange(user.getUserId(), "CREATE", null, user);
            autoGrantDefaultServices(user.getUserId());
            firstLogin = true;
            LOGGER.info("手机号自动注册: userId={}, phone={}", user.getUserId(), phone);
        }
        return new UserIdentityResult(user, firstLogin);
    }

    /**
     * 通过手机号合并已有账号
     * <p>
     * 检查合并目标账号状态，锁定账号不允许合并登录。
     */
    private UnifiedUserDO tryMergeByPhone(String phone) {
        UnifiedUserDO user = userMapper.selectByPhone(phone);
        if (user == null) {
            return null;
        }
        // 检查目标账号状态
        if (user.getStatus() != null && user.getStatus() == CasConstant.USER_STATUS_LOCKED) {
            throw new BusinessException(403, "关联账号已被锁定，请联系管理员");
        }
        if (user.getStatus() != null && user.getStatus() == CasConstant.USER_STATUS_DISABLED) {
            throw new BusinessException(403, "关联账号已被禁用，请联系管理员");
        }
        LOGGER.info("通过手机号合并账号: userId={}, phone={}", user.getUserId(), phone);
        return user;
    }

    /**
     * 生成唯一 userId
     */
    private String generateUserId() {
        return "U" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    /**
     * 安全创建用户（捕获并发 UNIQUE 冲突，重新查找合并）
     */
    private UnifiedUserDO safeCreate(UnifiedUserDO user) {
        try {
            userMapper.insert(user);
            return user;
        } catch (DuplicateKeyException e) {
            // 并发场景：另一个线程已用相同手机号创建了用户
            String phone = user.getPhone();
            if (StringUtils.isNotBlank(phone)) {
                UnifiedUserDO existing = userMapper.selectByPhone(phone);
                if (existing != null) {
                    LOGGER.warn("并发注册检测到已存在用户，合并: phone={}, existingUserId={}", phone, existing.getUserId());
                    return existing;
                }
            }
            LOGGER.error("并发注册但无法合并，抛出异常", e);
            throw new BusinessException(500, "注册失败，请重试");
        }
    }

    /**
     * 清洗客户端上传的昵称，过滤占位文案和无意义值。
     */
    private String sanitizeNickname(String nickname) {
        String normalized = StringUtils.trimToNull(nickname);
        if (normalized == null) {
            return null;
        }
        return INVALID_NICKNAMES.contains(normalized) ? null : normalized;
    }

    /**
     * 当库内昵称为空或占位值时，用本次登录携带的有效昵称补写。
     */
    private boolean tryRefreshNickname(UnifiedUserDO user, String incomingNickname) {
        if (user == null || incomingNickname == null) {
            return false;
        }
        if (sanitizeNickname(user.getNickname()) != null) {
            return false;
        }
        user.setNickname(incomingNickname);
        return true;
    }

    /**
     * 补全微信渠道身份。
     *
     * <p>当账号已经存在时，如果库里还没有 openid / unionid，
     * 则使用本次微信登录返回的值进行补写，避免“首次微信登录”后渠道身份仍缺失。</p>
     *
     * @param user 当前用户
     * @param openid 当前微信 openid
     * @param unionid 当前微信 unionid
     * @return true 表示发生了更新
     */
    private boolean syncWechatIdentity(UnifiedUserDO user, String openid, String unionid) {
        boolean updated = false;
        if (StringUtils.isNotBlank(openid) && StringUtils.isBlank(user.getOpenid())) {
            user.setOpenid(openid);
            updated = true;
        }
        if (StringUtils.isNotBlank(unionid) && StringUtils.isBlank(user.getUnionid())) {
            user.setUnionid(unionid);
            updated = true;
        }
        return updated;
    }

    private UnifiedUserDO createWechatUser(String openid, String unionid, String phone,
                                            String userType, String nickname) {
        UnifiedUserDO user = new UnifiedUserDO();
        user.setUserId(generateUserId());
        user.setOpenid(openid);
        user.setUnionid(unionid);
        user.setLoginType("WECHAT");
        user.setUserType(UserTypeUtils.normalize(userType));
        user.setSource("MINIAPP");
        user.setNickname(nickname);
        if (StringUtils.isNotBlank(phone)) {
            user.setPhone(phone);
            user.setPhoneVerified(true);
        }
        user.setStatus(CasConstant.USER_STATUS_ACTIVE);
        user.setFailedLoginCount(0);
        user.setTokenVersion(1);
        return safeCreate(user);
    }

    private UnifiedUserDO createDouyinUser(String openid, String unionid,
                                            String userType, String nickname) {
        UnifiedUserDO user = new UnifiedUserDO();
        user.setUserId(generateUserId());
        user.setDouyinOpenid(openid);
        user.setDouyinUnionid(unionid);
        user.setLoginType("DOUYIN_MINI");
        user.setUserType(UserTypeUtils.normalize(userType));
        user.setSource("MINIAPP");
        user.setNickname(nickname);
        user.setStatus(CasConstant.USER_STATUS_ACTIVE);
        user.setFailedLoginCount(0);
        user.setTokenVersion(1);
        return safeCreate(user);
    }

    private UnifiedUserDO createAlipayUser(String alipayUid, String userType, String nickname) {
        UnifiedUserDO user = new UnifiedUserDO();
        user.setUserId(generateUserId());
        user.setAlipayUid(alipayUid);
        user.setLoginType("ALIPAY_MINI");
        user.setUserType(UserTypeUtils.normalize(userType));
        user.setSource("MINIAPP");
        user.setNickname(nickname);
        user.setStatus(CasConstant.USER_STATUS_ACTIVE);
        user.setFailedLoginCount(0);
        user.setTokenVersion(1);
        return safeCreate(user);
    }

    private UnifiedUserDO createPhoneUser(String phone, String userType) {
        UnifiedUserDO user = new UnifiedUserDO();
        user.setUserId(generateUserId());
        user.setPhone(phone);
        user.setPhoneVerified(true);
        user.setLoginType("PHONE_SMS");
        user.setUserType(UserTypeUtils.normalize(userType));
        user.setSource("SMS");
        user.setNickname("用户" + phone.substring(Math.max(0, phone.length() - 4)));
        user.setStatus(CasConstant.USER_STATUS_ACTIVE);
        user.setFailedLoginCount(0);
        user.setTokenVersion(1);
        return safeCreate(user);
    }

    private void autoGrantDefaultServices(String userId) {
        if (StringUtils.isBlank(autoGrantServiceIds)) {
            return;
        }
        for (String serviceIdText : autoGrantServiceIds.split(",")) {
            if (StringUtils.isBlank(serviceIdText)) {
                continue;
            }
            try {
                Long serviceId = Long.parseLong(serviceIdText.trim());
                appAccessService.autoGrantOnRegister(userId, serviceId);
            } catch (NumberFormatException e) {
                LOGGER.warn("忽略非法的自动授权服务ID: {}", serviceIdText);
            }
        }
    }

    /**
     * 重新加载最新的用户记录。
     *
     * <p>微信/抖音/支付宝登录完成后，可能会触发账号合并或渠道标识补写。
     * 为了避免把“内存里刚创建但未包含最新数据库字段”的对象直接返回给 CAS 主体，
     * 这里统一回库读取一次最新状态。</p>
     *
     * @param user 当前用户
     * @return 最新用户记录；如果回库失败，则退回原对象
     */
    private UnifiedUserDO reloadUser(UnifiedUserDO user) {
        if (user == null || StringUtils.isBlank(user.getUserId())) {
            return user;
        }

        UnifiedUserDO latestUser = userMapper.selectByUserId(user.getUserId());
        return latestUser != null ? latestUser : user;
    }
}
