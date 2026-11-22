package club.beenest.iam.authz;

import club.beenest.iam.authz.authentication.LoginFailureCounter;
import club.beenest.iam.authz.mfa.MfaService;
import club.beenest.iam.authz.mfa.TotpService;
import club.beenest.iam.authz.notification.SecurityAlertService;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.entity.UserCredential;
import club.beenest.iam.persistence.mapper.UserCredentialMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * MFA 绑定流程回归测试(内存 Map 模拟凭证表与 Redis)。
 * <p>历史缺陷:{@code selectByUserIdAndType} 带 {@code enabled = TRUE} 过滤,
 * 导致 confirmEnrollment 永远查不到 enabled=FALSE 的待确认凭证,MFA 绑定必然失败。
 * 本测试锁定完整生命周期,防止回归。</p>
 */
class MfaEnrollmentFlowTest {

    /** 内存凭证表替身:复现真实 Mapper 的启用态语义。 */
    static class FakeCredentialMapper implements UserCredentialMapper {
        private final List<UserCredential> rows = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong(1);

        @Override
        public UserCredential selectByUserIdAndType(Long userId, String credentialType) {
            return rows.stream()
                    .filter(r -> r.getUserId().equals(userId))
                    .filter(r -> r.getCredentialType().equals(credentialType))
                    .filter(r -> Boolean.TRUE.equals(r.getEnabled()))
                    .filter(r -> !Boolean.TRUE.equals(r.getDeleted()))
                    .max((a, b) -> Long.compare(a.getId(), b.getId()))
                    .orElse(null);
        }

        @Override
        public UserCredential selectLatestByUserIdAndType(Long userId, String credentialType) {
            return rows.stream()
                    .filter(r -> r.getUserId().equals(userId))
                    .filter(r -> r.getCredentialType().equals(credentialType))
                    .filter(r -> !Boolean.TRUE.equals(r.getDeleted()))
                    .max((a, b) -> Long.compare(a.getId(), b.getId()))
                    .orElse(null);
        }

        @Override
        public int insert(UserCredential userCredential) {
            userCredential.setId(seq.getAndIncrement());
            userCredential.setCreatedTime(LocalDateTime.now());
            rows.add(userCredential);
            return 1;
        }

        @Override
        public int disableByUserIdAndType(Long userId, String credentialType) {
            int count = 0;
            for (UserCredential r : rows) {
                if (r.getUserId().equals(userId) && r.getCredentialType().equals(credentialType)
                        && Boolean.TRUE.equals(r.getEnabled()) && !Boolean.TRUE.equals(r.getDeleted())) {
                    r.setEnabled(false);
                    count++;
                }
            }
            return count;
        }

        @Override
        public int softDeletePendingByUserIdAndType(Long userId, String credentialType) {
            int count = 0;
            for (UserCredential r : rows) {
                if (r.getUserId().equals(userId) && r.getCredentialType().equals(credentialType)
                        && Boolean.FALSE.equals(r.getEnabled()) && !Boolean.TRUE.equals(r.getDeleted())) {
                    r.setDeleted(true);
                    count++;
                }
            }
            return count;
        }

        @Override
        public long countEnabledUsersByType(Long realmId, String credentialType) {
            return rows.stream()
                    .filter(r -> r.getCredentialType().equals(credentialType))
                    .filter(r -> Boolean.TRUE.equals(r.getEnabled()))
                    .filter(r -> !Boolean.TRUE.equals(r.getDeleted()))
                    .map(UserCredential::getUserId)
                    .distinct()
                    .count();
        }
    }

    /** 内存 Redis 替身:只实现防重放用到的方法。 */
    static class FakeRedis extends RedisUtils {
        private final Map<String, String> store = new ConcurrentHashMap<>();

        FakeRedis() {
            super(null, new ObjectMapper());
        }

        @Override
        public boolean hasKey(String key) {
            return store.containsKey(key);
        }

        @Override
        public void set(String key, String value, java.time.Duration ttl) {
            store.put(key, value);
        }

        @Override
        public boolean setIfAbsent(String key, String value, java.time.Duration ttl) {
            return store.putIfAbsent(key, value) == null;
        }

        @Override
        public boolean delete(String key) {
            return store.remove(key) != null;
        }

        @Override
        public Long delete(String... keys) {
            long count = 0;
            for (String key : keys) {
                if (store.remove(key) != null) {
                    count++;
                }
            }
            return count;
        }
    }

    private FakeCredentialMapper credentialMapper;
    private MfaService mfaService;

    @BeforeEach
    void setUp() {
        credentialMapper = new FakeCredentialMapper();
        UserProvider userProvider = new UserProvider() {
            @Override
            public Optional<UserModel> findByUsername(Long realmId, String username) {
                return Optional.empty();
            }

            @Override
            public Optional<UserModel> findByPhone(Long realmId, String phone) {
                return Optional.empty();
            }

            @Override
            public Optional<UserModel> findByEmail(Long realmId, String email) {
                return Optional.empty();
            }

            @Override
            public Optional<UserModel> findById(Long id) {
                return Optional.empty();
            }

            @Override
            public boolean verifyPassword(Long userId, String rawPassword) {
                return false;
            }

            @Override
            public boolean hasLocalPassword(Long userId) {
                return false;
            }

            @Override
            public void incrementTokenVersion(Long userId) {
            }

            @Override
            public UserModel createFederatedUser(Long realmId, String username, String email, String nickname,
                                                 String verifiedPhone, Map<String, String> attributes) {
                return null;
            }

            @Override
            public void updateVerifiedPhone(Long userId, String phone) {
            }
        };
        mfaService = new MfaService(credentialMapper, new FakeRedis(), new BCryptPasswordEncoder(),
                new ObjectMapper(), new LoginFailureCounter(new FakeRedis()), userProvider,
                mock(SecurityAlertService.class));
    }

    @Test
    @DisplayName("完整生命周期:绑定→确认→登录校验→防重放→关闭")
    void fullLifecycle() {
        Long userId = 1L;
        // 1. 初始状态:未启用
        assertFalse(mfaService.isEnrolled(userId));

        // 2. 开始绑定:返回密钥与 URI,凭证为待确认态
        MfaService.TotpEnrollment enrollment = mfaService.beginEnrollment(1L, userId, "zhangsan", "beenest-iam");
        assertTrue(enrollment.secret().matches("[A-Z2-7]{32}"));
        assertTrue(enrollment.provisioningUri().startsWith("otpauth://totp/"));
        assertFalse(mfaService.isEnrolled(userId));

        // 3. 未确认时登录第二步应拒绝
        assertFalse(mfaService.verifyLoginCode("default", userId, "000000"));

        // 4. 错误码确认失败
        assertThrows(Exception.class, () -> mfaService.confirmEnrollment(userId, "000000"));

        // 5. 正确动态口令确认:发放 10 个恢复码
        String code = TotpService.generateCode(enrollment.secret(),
                System.currentTimeMillis() / 1000 / TotpService.DEFAULT_PERIOD);
        List<String> recoveryCodes = mfaService.confirmEnrollment(userId, code);
        assertEquals(10, recoveryCodes.size());
        assertTrue(mfaService.isEnrolled(userId));

        // 6. 登录第二步:动态口令通过
        String loginCode = TotpService.generateCode(enrollment.secret(),
                System.currentTimeMillis() / 1000 / TotpService.DEFAULT_PERIOD);
        assertTrue(mfaService.verifyLoginCode("default", userId, loginCode));

        // 6b. 防重放:同一码在 90 秒窗口内再次使用应拒绝
        assertFalse(mfaService.verifyLoginCode("default", userId, loginCode));

        // 7. 防重放:同一码再次使用应拒绝(等窗口自然过期不现实,直接验证机制存在)
        //    换用恢复码:一次性消费
        String recovery = recoveryCodes.get(0);
        assertTrue(mfaService.verifyLoginCode("default", userId, recovery));
        assertFalse(mfaService.verifyLoginCode("default", userId, recovery));

        // 8. 已启用时不允许重复绑定
        assertThrows(Exception.class, () -> mfaService.beginEnrollment(1L, userId, "zhangsan", "beenest-iam"));

        // 9. 用恢复码关闭 MFA
        mfaService.disable(userId, recoveryCodes.get(1));
        assertFalse(mfaService.isEnrolled(userId));
    }

    @Test
    @DisplayName("重复绑定:旧待确认凭证被清理,新密钥生效")
    void reEnrollCleansPending() {
        Long userId = 2L;
        MfaService.TotpEnrollment first = mfaService.beginEnrollment(1L, userId, "lisi", "beenest-iam");
        // 未确认就再次绑定:应拿到新密钥且不抛异常
        MfaService.TotpEnrollment second = mfaService.beginEnrollment(1L, userId, "lisi", "beenest-iam");
        assertFalse(first.secret().equals(second.secret()));
        // 新密钥可正常走完确认流程
        String code = TotpService.generateCode(second.secret(),
                System.currentTimeMillis() / 1000 / TotpService.DEFAULT_PERIOD);
        assertTrue(mfaService.confirmEnrollment(userId, code).size() == 10);
        assertTrue(mfaService.isEnrolled(userId));
        // 旧密钥已作废:不能再用于登录
        String staleCode = TotpService.generateCode(first.secret(),
                System.currentTimeMillis() / 1000 / TotpService.DEFAULT_PERIOD);
        assertFalse(mfaService.verifyLoginCode("default", userId, staleCode));
    }

    @Test
    @DisplayName("无待确认凭证时确认绑定报状态冲突")
    void confirmWithoutPending() {
        assertThrows(Exception.class, () -> mfaService.confirmEnrollment(99L, "123456"));
    }
}
