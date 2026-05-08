package org.apereo.cas.beenest.service;

import org.apereo.cas.acct.AccountRegistrationRequest;
import org.apereo.cas.acct.AccountRegistrationResponse;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BeenestAccountRegistrationProvisionerTest {

    @Mock
    private UnifiedUserMapper userMapper;

    @Test
    void provisionsNativeRegistrationIntoCasUser() throws Throwable {
        AccountRegistrationRequest request = new AccountRegistrationRequest();
        request.putProperty("username", "pilot01");
        request.putProperty("password", "Pilot123!");
        request.putProperty("firstName", "张");
        request.putProperty("lastName", "三");
        request.putProperty("email", "pilot01@example.com");
        request.putProperty("phone", "13800138000");
        request.putProperty("userType", "PILOT");

        BeenestAccountRegistrationProvisioner provisioner = new BeenestAccountRegistrationProvisioner(userMapper);
        AccountRegistrationResponse response = provisioner.provision(request);

        assertThat(response.isSuccess()).isTrue();
        ArgumentCaptor<UnifiedUserDO> captor = ArgumentCaptor.forClass(UnifiedUserDO.class);
        verify(userMapper).insert(captor.capture());
        UnifiedUserDO user = captor.getValue();
        assertThat(user.getUserId()).startsWith("U");
        assertThat(user.getUsername()).isEqualTo("pilot01");
        assertThat(user.getNickname()).isEqualTo("张三");
        assertThat(user.getEmail()).isEqualTo("pilot01@example.com");
        assertThat(user.getPhone()).isEqualTo("13800138000");
        assertThat(user.getUserType()).isEqualTo("PILOT");
        assertThat(user.getSource()).isEqualTo("WEB");
        assertThat(user.getLoginType()).isEqualTo("USERNAME_PASSWORD");
        assertThat(new BCryptPasswordEncoder().matches("Pilot123!", user.getPasswordHash())).isTrue();
    }

    @Test
    void preventsClientAdminRegistration() throws Throwable {
        AccountRegistrationRequest request = new AccountRegistrationRequest();
        request.putProperty("username", "someone");
        request.putProperty("password", "Pilot123!");
        request.putProperty("email", "someone@example.com");
        request.putProperty("userType", "ADMIN");

        BeenestAccountRegistrationProvisioner provisioner = new BeenestAccountRegistrationProvisioner(userMapper);
        AccountRegistrationResponse response = provisioner.provision(request);

        assertThat(response.isSuccess()).isTrue();
        ArgumentCaptor<UnifiedUserDO> captor = ArgumentCaptor.forClass(UnifiedUserDO.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserType()).isEqualTo("CUSTOMER");
    }

    @Test
    void rejectsDuplicateUsernameBeforeInsert() throws Throwable {
        UnifiedUserDO existing = new UnifiedUserDO();
        existing.setUserId("U10001");
        when(userMapper.selectByUsername("pilot01")).thenReturn(existing);

        AccountRegistrationRequest request = new AccountRegistrationRequest();
        request.putProperty("username", "pilot01");
        request.putProperty("password", "Pilot123!");
        request.putProperty("email", "pilot01@example.com");

        BeenestAccountRegistrationProvisioner provisioner = new BeenestAccountRegistrationProvisioner(userMapper);
        AccountRegistrationResponse response = provisioner.provision(request);

        assertThat(response.isFailure()).isTrue();
        verify(userMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }
}
