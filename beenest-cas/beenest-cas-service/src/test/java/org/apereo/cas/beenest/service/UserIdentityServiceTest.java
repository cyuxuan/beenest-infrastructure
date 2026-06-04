package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.common.exception.BizException;
import org.apereo.cas.beenest.config.AutoGrantProperties;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserIdentityServiceTest {

    @Mock
    private UnifiedUserMapper userMapper;

    private AutoGrantProperties autoGrantProperties;

    @BeforeEach
    void setUp() {
        autoGrantProperties = new AutoGrantProperties();
        autoGrantProperties.setAutoGrantServiceIds(Set.of(10001L));
        autoGrantProperties.setAutoGrantRoles(Map.of(
                10001L, "ROLE_DRONE_SYSTEM",
                10003L, "ROLE_PAYMENT"
        ));
    }

    @Test
    void rejectsDisabledPhoneLogin() {
        UnifiedUserDO user = user("U10001", CasConstant.USER_STATUS_DISABLED);
        when(userMapper.selectByPhone("13800138000")).thenReturn(user);
        when(userMapper.selectByUserId("U10001")).thenReturn(user);

        UserIdentityService service = new UserIdentityService(userMapper, autoGrantProperties);

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

        UserIdentityService service = new UserIdentityService(userMapper, autoGrantProperties);

        assertThatThrownBy(() -> service.findOrRegisterByWechatResult(
                "openid-1", "union-1", null, "CUSTOMER", "张三"))
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

        UserIdentityService service = new UserIdentityService(userMapper, autoGrantProperties);

        // 解锁后应正常返回，不再抛出 BizException
        UserIdentityService.UserIdentityResult result =
                service.findOrRegisterByPhoneResult("13800138000", "PILOT");
        assertThat(result.user().getUserId()).isEqualTo("U10003");
        assertThat(result.user().getStatus()).isEqualTo(CasConstant.USER_STATUS_ACTIVE);
    }

    private UnifiedUserDO user(String userId, int status) {
        UnifiedUserDO user = new UnifiedUserDO();
        user.setId(1L);
        user.setUserId(userId);
        user.setStatus(status);
        return user;
    }
}