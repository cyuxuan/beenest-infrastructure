package club.beenest.iam.authz.sms;

import club.beenest.iam.authz.config.IamSmsProperties;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.common.web.IamRateLimiter;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.core.spi.sms.SmsChannel;
import club.beenest.iam.persistence.entity.LoginPolicy;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 短信验证码服务单元测试。
 * <p>固化四条安全口径:域开关、发送不可区分(未注册静默)、
 * 一次性消费、失败计数封顶;外加限流与"发送失败清码"。</p>
 */
class SmsCodeServiceTest {

    private static final String REALM = "default";
    private static final String PHONE = "13800138000";

    private FakeSmsRedis redis;
    private IamSecurityPolicyManager policyManager;
    private RealmProvider realmProvider;
    private UserProvider userProvider;
    private SmsChannelRouter router;
    private IamRateLimiter rateLimiter;
    private RecordingSmsChannel channel;
    private SmsCodeService service;

    @BeforeEach
    void setUp() {
        redis = new FakeSmsRedis();
        policyManager = Mockito.mock(IamSecurityPolicyManager.class);
        realmProvider = Mockito.mock(RealmProvider.class);
        userProvider = Mockito.mock(UserProvider.class);
        rateLimiter = Mockito.mock(IamRateLimiter.class);
        channel = new RecordingSmsChannel();
        router = new SmsChannelRouter(List.of(channel), smsProperties("aliyun"));
        service = new SmsCodeService(policyManager, realmProvider, userProvider, router,
                redis, rateLimiter);

        when(policyManager.getLoginPolicy(1L, REALM)).thenReturn(enabledPolicy());
        when(realmProvider.findByRealmKey(REALM))
                .thenReturn(Optional.of(new RealmModel(1L, REALM, "默认域", true, true,
                        300L, 1800L, 1800L, 36000L, null, null)));
    }

    /** 构造一个与正确码不同的 6 位码,避免随机码恰好等于占位值。 */
    private static String wrongCode(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private LoginPolicy enabledPolicy() {
        LoginPolicy policy = new LoginPolicy();
        policy.setSmsLoginEnabled(true);
        policy.setSmsCodeTtlSeconds(300);
        policy.setSmsMaxSendPerHour(3);
        policy.setSmsMaxVerifyAttempts(3);
        return policy;
    }

    private void givenRegisteredUser() {
        when(userProvider.findByPhone(1L, PHONE))
                .thenReturn(Optional.of(new UserModel(7L, 1L, "alice", "alice",
                        null, PHONE, 1, "INTERNAL", false, 0, Map.of())));
    }

    @Test
    @DisplayName("域开关关闭:发送与登录都明确拒绝,不静默")
    void rejectsWhenRealmSwitchOff() {
        LoginPolicy off = enabledPolicy();
        off.setSmsLoginEnabled(false);
        when(policyManager.getLoginPolicy(1L, REALM)).thenReturn(off);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.sendCode(REALM, PHONE, "10.0.0.1"));
        assertEquals(ErrorCode.SMS_CHANNEL_NOT_CONFIGURED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("手机号格式错误:参数错误,不进通道")
    void rejectsBadPhoneFormat() {
        assertThrows(BusinessException.class,
                () -> service.sendCode(REALM, "12345", "10.0.0.1"));
        assertTrue(channel.sent.isEmpty());
    }

    @Test
    @DisplayName("通道未配置:明确报功能未启用")
    void rejectsWhenChannelMissing() {
        SmsCodeService noChannel = new SmsCodeService(policyManager, realmProvider, userProvider,
                new SmsChannelRouter(List.of(channel), smsProperties(null)), redis, rateLimiter);
        givenRegisteredUser();
        BusinessException e = assertThrows(BusinessException.class,
                () -> noChannel.sendCode(REALM, PHONE, "10.0.0.1"));
        assertEquals(ErrorCode.SMS_CHANNEL_NOT_CONFIGURED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("未注册手机号:静默不发送,响应与已发送不可区分")
    void silentlySkipsUnknownPhone() {
        when(userProvider.findByPhone(1L, PHONE)).thenReturn(Optional.empty());
        service.sendCode(REALM, PHONE, "10.0.0.1");
        assertTrue(channel.sent.isEmpty(), "未注册号码不得发出短信");
        assertNull(redis.store.get("iam:default:smscode:" + PHONE), "未注册号码不得落码");
    }

    @Test
    @DisplayName("正常发送:码入库并送达通道")
    void sendsCodeForRegisteredPhone() {
        givenRegisteredUser();
        service.sendCode(REALM, PHONE, "10.0.0.1");
        assertEquals(1, channel.sent.size());
        String code = redis.store.get("iam:default:smscode:" + PHONE);
        assertTrue(code != null && code.matches("\\d{6}"), "6 位数字验证码");
    }

    @Test
    @DisplayName("每小时配额耗尽:拒绝且不再发送")
    void enforcesHourlyQuota() {
        givenRegisteredUser();
        for (int i = 0; i < 3; i++) {
            service.sendCode(REALM, PHONE, "10.0.0.2"); // 换 IP 绕开 IP 限流
            redis.store.remove("iam:ratelimit:sms-resend:default:" + PHONE);
        }
        assertEquals(3, channel.sent.size());
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.sendCode(REALM, PHONE, "10.0.0.3"));
        assertEquals(ErrorCode.SMS_SEND_TOO_FREQUENT.getCode(), e.getCode());
    }

    @Test
    @DisplayName("通道发送失败:已入库的验证码必须删除,不能留下收不到的死码")
    void deletesCodeWhenSendFails() {
        givenRegisteredUser();
        channel.failNext = true;
        assertThrows(BusinessException.class,
                () -> service.sendCode(REALM, PHONE, "10.0.0.1"));
        assertNull(redis.store.get("iam:default:smscode:" + PHONE));
    }

    @Test
    @DisplayName("校验成功一次性消费:重放同一枚码第二次必失败")
    void codeIsConsumedOnSuccess() {
        givenRegisteredUser();
        service.sendCode(REALM, PHONE, "10.0.0.1");
        String code = channel.sent.get(0);
        assertTrue(service.verifyCode(REALM, PHONE, code));
        assertFalse(service.verifyCode(REALM, PHONE, code), "一次性凭证不得重放");
    }

    @Test
    @DisplayName("校验失败计数:达到策略上限后作废失败计数")
    void countsFailedAttempts() {
        givenRegisteredUser();
        service.sendCode(REALM, PHONE, "10.0.0.1");
        for (int i = 0; i < 3; i++) {
            assertFalse(service.verifyCode(REALM, PHONE, "000000"));
        }
        // 3 次失败已达上限(smsMaxVerifyAttempts=3):计数键被清理
        assertNull(redis.store.get("iam:default:smscode:attempts:" + PHONE));
    }

    @Test
    @DisplayName("未达尝试上限时码不作废,正确码仍可通过")
    void keepsCodeBeforeAttemptLimit() {
        givenRegisteredUser();
        service.sendCode(REALM, PHONE, "10.0.0.1");
        String code = channel.sent.get(0);

        assertFalse(service.verifyCode(REALM, PHONE, wrongCode(code)));
        assertTrue(service.verifyCode(REALM, PHONE, code), "未达上限前码应仍然有效");
    }

    @Test
    @DisplayName("达到尝试上限后码立即作废,正确码也不再通过")
    void invalidatesCodeAtAttemptLimit() {
        givenRegisteredUser();
        service.sendCode(REALM, PHONE, "10.0.0.1");
        String code = channel.sent.get(0);

        for (int i = 0; i < 3; i++) {
            assertFalse(service.verifyCode(REALM, PHONE, wrongCode(code)));
        }
        assertFalse(service.verifyCode(REALM, PHONE, code), "达到上限后该码必须作废");
        assertNull(redis.store.get("iam:default:smscode:" + PHONE));
    }

    @Test
    @DisplayName("空验证码/空手机号直接拒绝,不触碰 Redis")
    void rejectsBlankInput() {
        assertFalse(service.verifyCode(REALM, PHONE, "  "));
        assertFalse(service.verifyCode(REALM, null, "123456"));
    }

    @Test
    @DisplayName("IP 维度限流由入口统一执行")
    void appliesIpRateLimit() {
        givenRegisteredUser();
        doThrow(new BusinessException(ErrorCode.TOO_MANY_REQUESTS))
                .when(rateLimiter).check(anyString(), anyString(), anyInt(), any(Duration.class));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.sendCode(REALM, PHONE, "10.0.0.9"));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), e.getCode());
        assertTrue(channel.sent.isEmpty(), "限流命中后不得到达通道");
    }

    // ===== 测试替身 =====

    private IamSmsProperties smsProperties(String channelName) {
        IamSmsProperties properties = new IamSmsProperties();
        properties.setChannel(channelName);
        properties.setAccessKeyId("id");
        properties.setAccessKeySecret("secret");
        properties.setSignName("蜂巢身份");
        properties.setTemplateCode("SMS_1");
        return properties;
    }

    /** 记录发送内容的通道替身;failNext 置位后下一次发送抛业务异常。 */
    static class RecordingSmsChannel implements SmsChannel {
        final List<String> sent = new java.util.ArrayList<>();
        boolean failNext;

        @Override
        public String channelId() {
            return "aliyun";
        }

        @Override
        public void send(String phone, String code, String realmKey) {
            if (failNext) {
                failNext = false;
                throw new BusinessException(ErrorCode.SMS_SEND_FAILED, "模拟通道故障");
            }
            sent.add(code);
        }
    }

    /** 内存 Redis 替身:覆盖短信链路用到的方法,TTL 在内存替身里不生效(即时)。 */
    static class FakeSmsRedis extends RedisUtils {
        final Map<String, String> store = new ConcurrentHashMap<>();
        final Map<String, Long> counters = new ConcurrentHashMap<>();

        FakeSmsRedis() {
            super(null, new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            store.put(key, value);
        }

        @Override
        public String get(String key) {
            String value = store.get(key);
            if (value != null) {
                return value;
            }
            // 计数器键:increment 只写 counters,读取侧要能看到,否则配额判断永远为 0
            Long count = counters.get(key);
            return count == null ? null : String.valueOf(count);
        }

        @Override
        public String getAndDelete(String key) {
            return store.remove(key);
        }

        @Override
        public boolean delete(String key) {
            return store.remove(key) != null;
        }

        @Override
        public long increment(String key) {
            return counters.merge(key, 1L, Long::sum);
        }

        @Override
        public long incrementWithTtl(String key, Duration ttl) {
            return increment(key);
        }

        @Override
        public Long delete(String... keys) {
            long removed = 0;
            for (String key : keys) {
                if (store.remove(key) != null) {
                    removed++;
                }
            }
            return removed;
        }
    }
}
