package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.common.exception.BizException;
import org.apereo.cas.beenest.config.AutoGrantProperties;
import org.apereo.cas.beenest.config.MiniAppProperties;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserIdentityServiceTest {

    @Mock
    private UnifiedUserMapper userMapper;

    @Mock
    private MiniAppProperties miniAppProperties;

    private AutoGrantProperties autoGrantProperties;

    @BeforeEach
    void setUp() {
        autoGrantProperties = new AutoGrantProperties();
        autoGrantProperties.setAutoGrant(List.of(
                new AutoGrantProperties.AutoGrantRule() {{
                    setServiceIds(List.of(10001L, 10002L, 10004L));
                    setRoles(List.of("ROLE_DRONE_SYSTEM", "ROLE_PAYMENT"));
                }},
                new AutoGrantProperties.AutoGrantRule() {{
                    setServiceIds(List.of(10003L));
                    setRoles(List.of("ROLE_PAYMENT"));
                }}
        ));
    }

    @Test
    void rejectsDisabledPhoneLogin() {
        UnifiedUserDO user = user("U10001", CasConstant.USER_STATUS_DISABLED);
        when(userMapper.selectByPhone("13800138000")).thenReturn(user);
        when(userMapper.selectByUserId("U10001")).thenReturn(user);

        UserIdentityService service = new UserIdentityService(userMapper, autoGrantProperties, miniAppProperties);

        assertThatThrownBy(() -> service.findOrRegisterByPhoneResult("13800138000", "PILOT"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("账号已禁用");
    }

    @Test
    void rejectsLockedWechatWithRemainingTime() {
        UnifiedUserDO user = user("U10002", CasConstant.USER_STATUS_LOCKED);
        user.setLockUntilTime(LocalDateTime.now().plusMinutes(25));
        when(userMapper.selectByUnionid("union-1")).thenReturn(user);
        when(userMapper.selectByUserId("U10002")).thenReturn(user);
        // unlockAccountIfNeeded 返回 0 表示仍在锁定期
        when(userMapper.unlockAccountIfNeeded(user.getId())).thenReturn(0);

        UserIdentityService service = new UserIdentityService(userMapper, autoGrantProperties, miniAppProperties);

        assertThatThrownBy(() -> service.findOrRegisterByWechatResult(
                "openid-1", "union-1", null, "CUSTOMER", "张三", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("账号已锁定")
                .hasMessageContaining("分钟后自动解锁");
    }

    @Test
    void autoUnlocksExpiredLockOnPhoneLogin() {
        UnifiedUserDO user = user("U10003", CasConstant.USER_STATUS_LOCKED);
        user.setLockUntilTime(LocalDateTime.now().minusMinutes(5));
        when(userMapper.selectByPhone("13800138000")).thenReturn(user);
        when(userMapper.selectByUserId("U10003")).thenReturn(user);
        // unlockAccountIfNeeded 返回 1 表示已成功解锁
        when(userMapper.unlockAccountIfNeeded(user.getId())).thenReturn(1);

        UserIdentityService service = new UserIdentityService(userMapper, autoGrantProperties, miniAppProperties);

        // 解锁后应正常返回，不再抛出 BizException
        UserIdentityService.UserIdentityResult result =
                service.findOrRegisterByPhoneResult("13800138000", "PILOT");
        assertThat(result.user().getUserId()).isEqualTo("U10003");
        assertThat(result.user().getStatus()).isEqualTo(CasConstant.USER_STATUS_ACTIVE);
    }

    @Test
    void backfillsPhoneOnChannelMatchedWechatLogin() {
        // 渠道匹配命中已有用户，且用户行尚无手机号 → 补写手机号
        UnifiedUserDO user = user("U10004", CasConstant.USER_STATUS_ACTIVE);
        user.setOpenid("openid-1");
        user.setUnionid("union-1");
        user.setNickname("张三");
        when(userMapper.selectByUnionid("union-1")).thenReturn(user);
        when(userMapper.selectByPhone("13800138000")).thenReturn(null);

        UserIdentityService service = new UserIdentityService(userMapper, autoGrantProperties, miniAppProperties);

        UserIdentityService.UserIdentityResult result = service.findOrRegisterByWechatResult(
                "openid-1", "union-1", "13800138000", "CUSTOMER", "张三", null);

        assertThat(result.user().getPhone()).isEqualTo("13800138000");
        assertThat(result.user().getPhoneVerified()).isTrue();
        verify(userMapper).updateByUserId(user);
    }

    @Test
    void skipsPhoneBackfillWhenPhoneOwnedByAnotherRow() {
        // 手机号已归属另一行（碎片数据）→ 跳过补写，不做破坏性合并
        UnifiedUserDO user = user("U10005", CasConstant.USER_STATUS_ACTIVE);
        user.setOpenid("openid-2");
        user.setUnionid("union-2");
        user.setNickname("李四");
        UnifiedUserDO holder = user("U99999", CasConstant.USER_STATUS_ACTIVE);
        holder.setPhone("13800138000");
        when(userMapper.selectByUnionid("union-2")).thenReturn(user);
        when(userMapper.selectByPhone("13800138000")).thenReturn(holder);

        UserIdentityService service = new UserIdentityService(userMapper, autoGrantProperties, miniAppProperties);

        UserIdentityService.UserIdentityResult result = service.findOrRegisterByWechatResult(
                "openid-2", "union-2", "13800138000", "CUSTOMER", "李四", null);

        assertThat(result.user().getPhone()).isNull();
        verify(userMapper, never()).updateByUserId(user);
    }

    @Test
    void skipsPhoneBackfillWhenUserAlreadyHasPhone() {
        // 用户行已有手机号 → 不重复补写
        UnifiedUserDO user = user("U10006", CasConstant.USER_STATUS_ACTIVE);
        user.setOpenid("openid-3");
        user.setUnionid("union-3");
        user.setNickname("王五");
        user.setPhone("13900139000");
        user.setPhoneVerified(true);
        when(userMapper.selectByUnionid("union-3")).thenReturn(user);

        UserIdentityService service = new UserIdentityService(userMapper, autoGrantProperties, miniAppProperties);

        UserIdentityService.UserIdentityResult result = service.findOrRegisterByWechatResult(
                "openid-3", "union-3", "13800138000", "CUSTOMER", "王五", null);

        // 保留原手机号，不覆盖为本次登录的手机号
        assertThat(result.user().getPhone()).isEqualTo("13900139000");
        verify(userMapper, never()).updateByUserId(user);
    }

    @Test
    void backfillsAvatarOnChannelMatchedWechatLogin() {
        // 渠道匹配命中已有用户，且用户行尚无头像 → 用本次登录携带的头像补写
        UnifiedUserDO user = user("U10007", CasConstant.USER_STATUS_ACTIVE);
        user.setOpenid("openid-4");
        user.setUnionid("union-4");
        user.setNickname("赵六");
        when(userMapper.selectByUnionid("union-4")).thenReturn(user);

        UserIdentityService service = new UserIdentityService(userMapper, autoGrantProperties, miniAppProperties);

        UserIdentityService.UserIdentityResult result = service.findOrRegisterByWechatResult(
                "openid-4", "union-4", null, "CUSTOMER", "赵六", "https://avatar.example/x.png");

        assertThat(result.user().getAvatarUrl()).isEqualTo("https://avatar.example/x.png");
        verify(userMapper).updateByUserId(user);
    }

    @Test
    void keepsExistingAvatarOnChannelMatchedWechatLogin() {
        // 用户行已有头像 → 不用本次登录的头像覆盖，避免冲掉业务侧已改的头像
        UnifiedUserDO user = user("U10008", CasConstant.USER_STATUS_ACTIVE);
        user.setOpenid("openid-5");
        user.setUnionid("union-5");
        user.setNickname("钱七");
        user.setAvatarUrl("https://avatar.example/original.png");
        when(userMapper.selectByUnionid("union-5")).thenReturn(user);

        UserIdentityService service = new UserIdentityService(userMapper, autoGrantProperties, miniAppProperties);

        UserIdentityService.UserIdentityResult result = service.findOrRegisterByWechatResult(
                "openid-5", "union-5", null, "CUSTOMER", "钱七", "https://avatar.example/new.png");

        assertThat(result.user().getAvatarUrl()).isEqualTo("https://avatar.example/original.png");
        verify(userMapper, never()).updateByUserId(user);
    }

    private UnifiedUserDO user(String userId, int status) {
        UnifiedUserDO user = new UnifiedUserDO();
        user.setId(1L);
        user.setUserId(userId);
        user.setStatus(status);
        return user;
    }
}
