package club.beenest.iam.authz.mfa;

import club.beenest.iam.authz.authentication.LoginFailureCounter;
import club.beenest.iam.authz.notification.SecurityAlertService;
import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.entity.UserCredential;
import club.beenest.iam.persistence.mapper.UserCredentialMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MFA 多因子认证服务。
 * <p>凭证模型(iam_user_credential):TOTP 凭证的 secret_data 为 Base32 密钥,algorithm=TOTP_SHA1,
 * 绑定确认前 enabled=FALSE;RECOVERY_CODE 凭证的 secret_data 为恢复码哈希 JSON 数组。</p>
 * <p>登录第二步接受 TOTP 码或恢复码:TOTP 码有 90 秒防重放窗口,恢复码一次性使用
 * (命中后从哈希集合中移除)。</p>
 */
@Service
public class MfaService {

    /** TOTP 凭证类型 */
    public static final String CREDENTIAL_TOTP = "TOTP";
    /** 恢复码凭证类型 */
    public static final String CREDENTIAL_RECOVERY_CODE = "RECOVERY_CODE";

    /** 一次性码防重放窗口(覆盖 ±1 步漂移) */
    private static final Duration CODE_REPLAY_WINDOW = Duration.ofSeconds(90);
    /** 恢复码消费锁的最长持有时间:进程异常退出时兜底释放,避免锁永久残留 */
    private static final Duration RECOVERY_LOCK_TTL = Duration.ofSeconds(10);
    /**
     * 凭证"长期有效"的落库表示。
     * <p>动态口令与恢复码不按时间过期,expired_time 统一写远期时间。</p>
     */
    private static final long NEVER_EXPIRE_YEARS = 100;

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    private final UserCredentialMapper credentialMapper;
    private final RedisUtils redisUtils;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final LoginFailureCounter loginFailureCounter;
    private final UserProvider userProvider;
    private final SecurityAlertService securityAlertService;

    public MfaService(UserCredentialMapper credentialMapper, RedisUtils redisUtils,
                      PasswordEncoder passwordEncoder, ObjectMapper objectMapper,
                      LoginFailureCounter loginFailureCounter, UserProvider userProvider,
                      SecurityAlertService securityAlertService) {
        this.credentialMapper = credentialMapper;
        this.redisUtils = redisUtils;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.loginFailureCounter = loginFailureCounter;
        this.userProvider = userProvider;
        this.securityAlertService = securityAlertService;
    }

    /**
     * MFA 状态。
     *
     * @param totpEnabled            TOTP 凭证是否已启用
     * @param recoveryCodesRemaining 剩余可用恢复码数量
     */
    public record MfaStatus(boolean totpEnabled, int recoveryCodesRemaining) {
    }

    /**
     * 绑定初始化结果,密钥仅此一次明文返回。
     *
     * @param secret          Base32 TOTP 密钥
     * @param provisioningUri otpauth 扫码地址
     */
    public record TotpEnrollment(String secret, String provisioningUri) {
    }

    /**
     * 查询用户 MFA 状态。
     *
     * @param userId 用户主键
     * @return TOTP 是否已启用、是否还有恢复码
     */
    public MfaStatus status(Long userId) {
        UserCredential totp = credentialMapper.selectByUserIdAndType(userId, CREDENTIAL_TOTP);
        UserCredential recovery = credentialMapper.selectByUserIdAndType(userId, CREDENTIAL_RECOVERY_CODE);
        boolean enabled = totp != null && Boolean.TRUE.equals(totp.getEnabled());
        int remaining = recovery == null ? 0 : parseHashes(recovery.getSecretData()).size();
        return new MfaStatus(enabled, remaining);
    }

    /**
     * 开始绑定:生成密钥并写入待启用凭证(enabled=FALSE)。
     * <p>重复调用会清理此前的待启用凭证并写入新密钥;已启用的 TOTP 处于冲突状态,
     * 需先调用关闭接口再重新绑定。</p>
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @param username 登录名(写入 provisioning URI)
     * @param issuer  签发方展示名;为空时使用 beenest-iam
     * @return 密钥与 provisioning URI,密钥仅此一次明文返回
     * @throws BusinessException 动态口令已启用
     */
    @Transactional
    public TotpEnrollment beginEnrollment(Long realmId, Long userId, String username, String issuer) {
        // 1. 已启用则不允许重复绑定;查最新凭证以区分"已启用"与"待启用"
        UserCredential existing = credentialMapper.selectLatestByUserIdAndType(userId, CREDENTIAL_TOTP);
        if (existing != null && Boolean.TRUE.equals(existing.getEnabled())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "动态口令已启用,如需重新绑定请先关闭");
        }
        // 2. 清理此前的待确认凭证(重复绑定时旧密钥作废),写入新密钥
        if (existing != null) {
            credentialMapper.softDeletePendingByUserIdAndType(userId, CREDENTIAL_TOTP);
        }
        String secret = TotpService.generateSecret();
        UserCredential credential = new UserCredential();
        credential.setRealmId(realmId);
        credential.setUserId(userId);
        credential.setCredentialType(CREDENTIAL_TOTP);
        credential.setSecretData(secret);
        credential.setAlgorithm("TOTP_SHA1");
        credential.setEnabled(false);
        credentialMapper.insert(credential);
        String uri = TotpService.provisioningUri(secret, username, issuer == null ? "beenest-iam" : issuer);
        return new TotpEnrollment(secret, uri);
    }

    /**
     * 确认绑定:校验用户输入的动态口令,启用凭证并发放恢复码。
     *
     * @param userId 用户主键
     * @param code   用户输入的动态口令
     * @return 恢复码明文列表,仅此一次返回
     * @throws BusinessException 没有待确认的绑定,或动态口令错误
     */
    @Transactional
    public List<String> confirmEnrollment(Long userId, String code) {
        // 待确认凭证 enabled=FALSE,必须用不滤启用态的查询
        UserCredential pending = credentialMapper.selectLatestByUserIdAndType(userId, CREDENTIAL_TOTP);
        if (pending == null || Boolean.TRUE.equals(pending.getEnabled())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "没有待确认的动态口令绑定");
        }
        if (!TotpService.verify(pending.getSecretData(), code)) {
            throw new BusinessException(ErrorCode.MFA_CODE_INVALID, "动态口令错误,请检查authenticator时间同步");
        }
        // 1. 启用凭证
        enableCredential(pending);
        // 2. 发放恢复码(覆盖旧恢复码)
        List<String> recoveryCodes = issueRecoveryCodes(pending.getRealmId(), userId);
        // 3. 第二因素变更提醒:绑上他人设备后,受害者只有从提醒里才能察觉
        securityAlertService.alertCredentialChanged(pending.getRealmId(), userId, "动态口令已启用",
                "你的账号刚刚绑定了新的动态口令(二次验证)。以后登录需要使用该动态口令或恢复码。");
        return recoveryCodes;
    }

    /**
     * 重新发放恢复码,旧的立即作废。
     *
     * @param userId 用户主键
     * @return 新的恢复码明文列表
     * @throws BusinessException 动态口令未启用
     */
    @Transactional
    public List<String> regenerateRecoveryCodes(Long userId) {
        UserCredential totp = requireEnabledTotp(userId);
        return issueRecoveryCodes(totp.getRealmId(), userId);
    }

    /**
     * 关闭 MFA:需通过一次动态口令或恢复码校验。
     *
     * @param userId 用户主键
     * @param code   动态口令或恢复码
     * @throws BusinessException 动态口令未启用,或校验不通过
     */
    @Transactional
    public void disable(Long userId, String code) {
        UserCredential totp = requireEnabledTotp(userId);
        boolean ok = TotpService.verify(totp.getSecretData(), code) || consumeRecoveryCode(userId, code);
        if (!ok) {
            throw new BusinessException(ErrorCode.MFA_CODE_INVALID, "动态口令或恢复码错误");
        }
        credentialMapper.disableByUserIdAndType(userId, CREDENTIAL_TOTP);
        credentialMapper.disableByUserIdAndType(userId, CREDENTIAL_RECOVERY_CODE);
        // 关闭第二因素会削弱账号防护,同样需要让本人知道
        securityAlertService.alertCredentialChanged(totp.getRealmId(), userId, "动态口令已关闭",
                "你的账号已关闭动态口令(二次验证),登录将只校验第一因素。");
    }

    /**
     * 登录第二步校验:动态口令(带防重放)或恢复码。
     *
     * @param realmKey 身份域键(失败计数维度)
     * @param userId   用户主键
     * @param code     动态口令或恢复码
     * @return 校验是否通过
     */
    public boolean verifyLoginCode(String realmKey, Long userId, String code) {
        // 1. 失败计数维度按登录名:由用户主键反查(票据中只含主键)
        String username = userProvider.findById(userId)
                .map(UserModel::username)
                .orElse(String.valueOf(userId));
        if (loginFailureCounter.isLocked(realmKey, username, null)) {
            return false;
        }
        UserCredential totp = credentialMapper.selectByUserIdAndType(userId, CREDENTIAL_TOTP);
        if (totp == null || !Boolean.TRUE.equals(totp.getEnabled())) {
            return false;
        }
        boolean ok;
        if (TotpService.verify(totp.getSecretData(), code)) {
            // 校验通过仍需防重放:同一码在窗口内只能使用一次,占位由 SET NX 原子完成
            ok = markUsedIfAbsent(realmKey, userId, code);
        } else {
            // 动态口令不通过时尝试恢复码(一次性)
            ok = consumeRecoveryCode(userId, code);
        }
        if (ok) {
            loginFailureCounter.onSuccess(realmKey, username);
        } else {
            loginFailureCounter.onFailure(realmKey, username, null);
        }
        return ok;
    }

    /**
     * 用户是否已启用 TOTP(登录链路据此判断是否需要第二步行)。
     *
     * @param userId 用户主键
     * @return 是否已启用
     */
    public boolean isEnrolled(Long userId) {
        UserCredential totp = credentialMapper.selectByUserIdAndType(userId, CREDENTIAL_TOTP);
        return totp != null && Boolean.TRUE.equals(totp.getEnabled());
    }

    // ===== 内部实现 =====

    /**
     * 取已启用的 TOTP 凭证。
     *
     * @param userId 用户主键
     * @return 已启用的 TOTP 凭证
     * @throws BusinessException 动态口令未启用
     */
    private UserCredential requireEnabledTotp(Long userId) {
        UserCredential totp = credentialMapper.selectByUserIdAndType(userId, CREDENTIAL_TOTP);
        if (totp == null || !Boolean.TRUE.equals(totp.getEnabled())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "动态口令未启用");
        }
        return totp;
    }

    /**
     * 启用待确认凭证:停用旧行后插入一条 enabled=TRUE 的新行,旧行保留用于追溯。
     *
     * @param credential 待确认的 TOTP 凭证
     */
    private void enableCredential(UserCredential credential) {
        // 通过停用+插入新行使 enabled=TRUE;停用旧行使历史可追溯
        credentialMapper.disableByUserIdAndType(credential.getUserId(), CREDENTIAL_TOTP);
        UserCredential enabled = new UserCredential();
        enabled.setRealmId(credential.getRealmId());
        enabled.setUserId(credential.getUserId());
        enabled.setCredentialType(CREDENTIAL_TOTP);
        enabled.setSecretData(credential.getSecretData());
        enabled.setAlgorithm(credential.getAlgorithm());
        enabled.setEnabled(true);
        enabled.setExpiredTime(noExpiry());
        credentialMapper.insert(enabled);
    }

    /**
     * 发放恢复码:明文返回一次,库中仅存哈希。
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @return 恢复码明文列表
     */
    private List<String> issueRecoveryCodes(Long realmId, Long userId) {
        List<String> codes = TotpService.generateRecoveryCodes();
        List<String> hashes = new ArrayList<>(codes.size());
        for (String code : codes) {
            hashes.add(passwordEncoder.encode(code));
        }
        credentialMapper.disableByUserIdAndType(userId, CREDENTIAL_RECOVERY_CODE);
        UserCredential credential = new UserCredential();
        credential.setRealmId(realmId);
        credential.setUserId(userId);
        credential.setCredentialType(CREDENTIAL_RECOVERY_CODE);
        credential.setSecretData(writeHashes(hashes));
        credential.setAlgorithm("RECOVERY_CODE");
        credential.setEnabled(true);
        credential.setExpiredTime(noExpiry());
        credentialMapper.insert(credential);
        return codes;
    }

    /**
     * 消费一个恢复码:命中即从集合移除,返回是否命中。
     * <p>"读取哈希集合 → 比对 → 重写剩余"是读-改-写序列,用 Redis 锁互斥,
     * 多实例部署下同一枚恢复码也不会被并发兑换两次。</p>
     *
     * @param userId 用户主键
     * @param code   恢复码
     * @return 是否命中
     */
    private boolean consumeRecoveryCode(Long userId, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String lockKey = String.format(IamRedisKeyConstants.MFA_RECOVERY_LOCK, userId);
        if (!redisUtils.setIfAbsent(lockKey, "1", RECOVERY_LOCK_TTL)) {
            log.warn("恢复码消费并发冲突,拒绝本次兑换: userId={}", userId);
            return false;
        }
        try {
            return doConsumeRecoveryCode(userId, code);
        } finally {
            redisUtils.delete(lockKey);
        }
    }

    /**
     * 在持有互斥锁的前提下执行恢复码兑换。
     *
     * @param userId 用户主键
     * @param code   恢复码
     * @return 是否命中
     */
    private boolean doConsumeRecoveryCode(Long userId, String code) {
        UserCredential credential = credentialMapper.selectByUserIdAndType(userId, CREDENTIAL_RECOVERY_CODE);
        if (credential == null || !Boolean.TRUE.equals(credential.getEnabled())) {
            return false;
        }
        List<String> hashes = parseHashes(credential.getSecretData());
        String matched = null;
        for (String hash : hashes) {
            if (passwordEncoder.matches(code.trim(), hash)) {
                matched = hash;
                break;
            }
        }
        if (matched == null) {
            return false;
        }
        hashes.remove(matched);
        // 重写剩余哈希:停用旧行写入新行
        credentialMapper.disableByUserIdAndType(userId, CREDENTIAL_RECOVERY_CODE);
        if (!hashes.isEmpty()) {
            UserCredential remaining = new UserCredential();
            remaining.setRealmId(credential.getRealmId());
            remaining.setUserId(userId);
            remaining.setCredentialType(CREDENTIAL_RECOVERY_CODE);
            remaining.setSecretData(writeHashes(hashes));
            remaining.setAlgorithm("RECOVERY_CODE");
            remaining.setEnabled(true);
            remaining.setExpiredTime(noExpiry());
            credentialMapper.insert(remaining);
        }
        return true;
    }

    /**
     * 原子占位一次性码:抢占成功返回 true,键已存在(重放)返回 false。
     * <p>由 Redis 的 SET NX 保证原子性,多实例部署下同样成立。</p>
     *
     * @param realmKey 身份域键
     * @param userId   用户主键
     * @param code     动态口令
     * @return 是否为首次使用
     */
    private boolean markUsedIfAbsent(String realmKey, Long userId, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return redisUtils.setIfAbsent(
                String.format(IamRedisKeyConstants.MFA_USED_CODE, realmKey, userId, code.trim()),
                "1", CODE_REPLAY_WINDOW);
    }

    /**
     * 凭证"长期有效"的到期时间。
     *
     * @return 当前时间加 100 年
     */
    private static LocalDateTime noExpiry() {
        return LocalDateTime.now().plusYears(NEVER_EXPIRE_YEARS);
    }

    /**
     * 解析恢复码哈希集合。
     *
     * @param json 哈希 JSON 数组
     * @return 哈希列表;入参为空或解析失败时返回空列表
     */
    private List<String> parseHashes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            // 解析失败时留痕,按无恢复码处理
            log.error("恢复码存储解析失败,按无恢复码处理", e);
            return List.of();
        }
    }

    /**
     * 序列化恢复码哈希集合。
     *
     * @param hashes 哈希列表
     * @return JSON 数组
     * @throws IllegalStateException 序列化失败
     */
    private String writeHashes(List<String> hashes) {
        try {
            return objectMapper.writeValueAsString(hashes);
        } catch (Exception e) {
            throw new IllegalStateException("恢复码序列化失败", e);
        }
    }
}
