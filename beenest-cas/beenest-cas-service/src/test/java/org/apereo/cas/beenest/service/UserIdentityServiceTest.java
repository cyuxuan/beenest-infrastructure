package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.common.exception.BizException;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserIdentityServiceTest {

    @Mock
    private UnifiedUserMapper userMapper;

    @Test
    void rejectsDisabledPhoneLogin() {
        UnifiedUserDO user = user("U10001", CasConstant.USER_STATUS_DISABLED);
        when(userMapper.selectByPhone("13800138000")).thenReturn(user);

        UserIdentityService service = new UserIdentityService(userMapper);

        assertThatThrownBy(() -> service.findOrRegisterByPhoneResult("13800138000", "PILOT"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("关联账号已被禁用");
    }

    @Test
    void rejectsLockedWechatDirectMatch() {
        UnifiedUserDO user = user("U10002", CasConstant.USER_STATUS_LOCKED);
        when(userMapper.selectByUnionid("union-1")).thenReturn(user);
        when(userMapper.selectByUserId("U10002")).thenReturn(user);

        UserIdentityService service = new UserIdentityService(userMapper);

        assertThatThrownBy(() -> service.findOrRegisterByWechatResult(
                "openid-1", "union-1", null, "CUSTOMER", "张三"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("关联账号已被锁定");
    }

    private UnifiedUserDO user(String userId, int status) {
        UnifiedUserDO user = new UnifiedUserDO();
        user.setUserId(userId);
        user.setStatus(status);
        return user;
    }
}
