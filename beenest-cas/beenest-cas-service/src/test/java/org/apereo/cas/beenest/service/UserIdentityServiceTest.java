package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserIdentityServiceTest {

    @Test
    void createsNewWechatUserForFirstThirdPartyRegistration() {
        UnifiedUserMapper userMapper = mock(UnifiedUserMapper.class);

        when(userMapper.selectByUnionid("union-1")).thenReturn(null);
        when(userMapper.selectByOpenid("openid-1", "WECHAT")).thenReturn(null);

        UserIdentityService service = new UserIdentityService(userMapper);

        UnifiedUserDO user = service.findOrRegisterByWechat("openid-1", "union-1", null, "hacker", "Alice");

        assertEquals("CUSTOMER", user.getUserType());
        verify(userMapper).insert(any());
    }

    @Test
    void refreshesPlaceholderNicknameWhenExistingWechatUserLogsIn() {
        UnifiedUserMapper userMapper = mock(UnifiedUserMapper.class);

        UnifiedUserDO existingUser = new UnifiedUserDO();
        existingUser.setUserId("U10001");
        existingUser.setOpenid("openid-1");
        existingUser.setNickname("未登录");
        existingUser.setUserType("CUSTOMER");

        when(userMapper.selectByUnionid("union-1")).thenReturn(null);
        when(userMapper.selectByOpenid("openid-1", "WECHAT")).thenReturn(existingUser);

        UserIdentityService service = new UserIdentityService(userMapper);

        UnifiedUserDO user = service.findOrRegisterByWechat("openid-1", "union-1", null, "CUSTOMER", "张三");

        assertSame(existingUser, user);
        assertEquals("张三", user.getNickname());
        verify(userMapper).updateByUserId(existingUser);
    }

    @Test
    void ignoresPlaceholderNicknameWhenExistingWechatUserAlreadyHasValidNickname() {
        UnifiedUserMapper userMapper = mock(UnifiedUserMapper.class);

        UnifiedUserDO existingUser = new UnifiedUserDO();
        existingUser.setUserId("U10002");
        existingUser.setOpenid("openid-2");
        existingUser.setUnionid("union-2");
        existingUser.setNickname("老王");
        existingUser.setUserType("CUSTOMER");

        when(userMapper.selectByUnionid("union-2")).thenReturn(null);
        when(userMapper.selectByOpenid("openid-2", "WECHAT")).thenReturn(existingUser);

        UserIdentityService service = new UserIdentityService(userMapper);

        UnifiedUserDO user = service.findOrRegisterByWechat("openid-2", "union-2", null, "CUSTOMER", "未登录");

        assertSame(existingUser, user);
        assertEquals("老王", user.getNickname());
        verify(userMapper, never()).updateByUserId(existingUser);
    }
}
