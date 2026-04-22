package org.apereo.cas.beenest.controller;

import org.apereo.cas.beenest.config.GlobalExceptionHandler;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.beenest.service.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserSyncControllerTest {

    private MockMvc mockMvc;
    private UserSyncController controller;

    @BeforeEach
    void setUp() {
        UnifiedUserMapper userMapper = mock(UnifiedUserMapper.class);
        UserSyncService userSyncService = mock(UserSyncService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofMinutes(5)))).thenReturn(true);
        controller = new UserSyncController(userMapper, userSyncService, redisTemplate);
        ReflectionTestUtils.setField(controller, "signKey", "");
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void rejectsMissingSignatureHeaders() throws Exception {
        ReflectionTestUtils.setField(controller, "signKey", "test-secret");
        mockMvc.perform(get("/api/user/U1001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("缺少签名头 X-CAS-Timestamp 或 X-CAS-Signature"));
    }

    @Test
    void rejectsMissingSyncSigningKey() throws Exception {
        mockMvc.perform(get("/api/user/U1001")
                        .header("X-CAS-Timestamp", String.valueOf(System.currentTimeMillis()))
                        .header("X-CAS-Signature", "demo-signature"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("用户同步签名密钥未配置"));
    }
}
