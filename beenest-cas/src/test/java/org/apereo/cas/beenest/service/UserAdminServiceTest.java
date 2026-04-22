package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAdminServiceTest {

    @Test
    void batchGetUsersUsesSingleBatchMapperCall() {
        UnifiedUserMapper userMapper = mock(UnifiedUserMapper.class);
        UserSyncService userSyncService = mock(UserSyncService.class);
        AppAccessService appAccessService = mock(AppAccessService.class);
        UserAdminService service = new UserAdminService(userMapper, userSyncService, appAccessService);

        UnifiedUserDO user = new UnifiedUserDO();
        user.setUserId("U1001");
        when(userMapper.selectByUserIds(List.of("U1001", "U1002"))).thenReturn(List.of(user));

        List<UnifiedUserDO> users = service.batchGetUsers(List.of("U1001", "U1002"));

        assertThat(users).hasSize(1);
        verify(userMapper).selectByUserIds(List.of("U1001", "U1002"));
        verify(userMapper, never()).selectByUserId("U1001");
    }
}
