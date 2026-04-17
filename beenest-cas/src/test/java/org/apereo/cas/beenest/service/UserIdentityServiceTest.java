package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserIdentityServiceTest {

    @Test
    void autoGrantsConfiguredServicesForFirstThirdPartyRegistration() {
        UnifiedUserMapper userMapper = mock(UnifiedUserMapper.class);
        UserSyncService userSyncService = mock(UserSyncService.class);
        AppAccessService appAccessService = mock(AppAccessService.class);

        when(userMapper.selectByUnionid("union-1")).thenReturn(null);
        when(userMapper.selectByOpenid("openid-1", "WECHAT")).thenReturn(null);

        UserIdentityService service = new UserIdentityService(
                userMapper, userSyncService, appAccessService, "10001, 10002");

        UnifiedUserDO user = service.findOrRegisterByWechat("openid-1", "union-1", null, "hacker", "Alice");

        assertEquals("CUSTOMER", user.getUserType());
        verify(appAccessService, times(1)).autoGrantOnRegister(anyString(), org.mockito.ArgumentMatchers.eq(10001L));
        verify(appAccessService, times(1)).autoGrantOnRegister(anyString(), org.mockito.ArgumentMatchers.eq(10002L));
    }
}
