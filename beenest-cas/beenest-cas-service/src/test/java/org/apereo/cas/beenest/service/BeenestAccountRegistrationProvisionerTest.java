package org.apereo.cas.beenest.service;

import org.apereo.cas.acct.AccountRegistrationRequest;
import org.apereo.cas.acct.AccountRegistrationResponse;
import org.apereo.cas.beenest.config.AutoGrantProperties;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BeenestAccountRegistrationProvisioner 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class BeenestAccountRegistrationProvisionerTest {

    @Mock
    private UnifiedUserMapper userMapper;

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
    void provisionsNativeRegistrationIntoCasUser() throws Throwable {
        AccountRegistrationRequest request = new AccountRegistrationRequest();
        request.putProperty("username", "pilot01");
        request.putProperty("password", "Pilot123!");
        request.putProperty("firstName", "张");
        request.putProperty("lastName", "三");
        request.putProperty("email", "pilot01@example.com");
        request.putProperty("phone", "13800138000");
        request.putProperty("userType", "PILOT");

        BeenestAccountRegistrationProvisioner provisioner =
                new BeenestAccountRegistrationProvisioner(userMapper, autoGrantProperties);
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
    void autoGrantsAllRolesFromAllRulesAfterSuccessfulRegistration() throws Throwable {
        AccountRegistrationRequest request = new AccountRegistrationRequest();
        request.putProperty("username", "pilot01");
        request.putProperty("password", "Pilot123!");
        request.putProperty("email", "pilot01@example.com");

        BeenestAccountRegistrationProvisioner provisioner =
                new BeenestAccountRegistrationProvisioner(userMapper, autoGrantProperties);
        AccountRegistrationResponse response = provisioner.provision(request);

        assertThat(response.isSuccess()).isTrue();
        // 规则1 授予 ROLE_DRONE_SYSTEM + ROLE_PAYMENT
        // 规则2 也授予 ROLE_PAYMENT（addRole SQL 有去重，不会重复插入）
        verify(userMapper, atLeastOnce()).addRole(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("ROLE_DRONE_SYSTEM"));
        verify(userMapper, atLeastOnce()).addRole(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("ROLE_PAYMENT"));
    }

    @Test
    void preventsClientAdminRegistration() throws Throwable {
        AccountRegistrationRequest request = new AccountRegistrationRequest();
        request.putProperty("username", "someone");
        request.putProperty("password", "Pilot123!");
        request.putProperty("email", "someone@example.com");
        request.putProperty("userType", "ADMIN");

        BeenestAccountRegistrationProvisioner provisioner =
                new BeenestAccountRegistrationProvisioner(userMapper, autoGrantProperties);
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

        BeenestAccountRegistrationProvisioner provisioner =
                new BeenestAccountRegistrationProvisioner(userMapper, autoGrantProperties);
        AccountRegistrationResponse response = provisioner.provision(request);

        assertThat(response.isFailure()).isTrue();
        verify(userMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        // 注册失败时不应自动赋权
        verify(userMapper, never()).addRole(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
