package club.beenest.iam.authz.sms;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.common.web.IamRateLimiter;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.core.spi.sms.SmsChannel;
import club.beenest.iam.persistence.entity.LoginPolicy;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 短信验证码服务(第一因素登录)。
 * <p>发码流程:域开关 → 号码格式 → 通道 → IP、手机号与每小时三重限流 → 仅向已注册号码发送;
 * 未注册号码静默返回,响应与发送成功一致。</p>
 * <p>验证码有效期由登录策略决定(默认 300 秒),校验时一次性消费;失败按手机号计数,
 * 达到策略阈值 sms_max_verify_attempts(默认 5)后清零计数,重新取码同样清零。</p>
 */
@Service
public class SmsCodeService {

    /** 大陆手机号格式 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    /** 验证码位数 */
    private static final int CODE_LENGTH = 6;
    /** 同一手机号两次发送的最小间隔(防连点"获取验证码") */
    private static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);
    /** IP 维度发送限流窗口 */
    private static final Duration IP_SEND_WINDOW = Duration.ofMinutes(1);
    /** IP 维度每分钟最大发送次数 */
    private static final int IP_SEND_LIMIT = 5;
    /** 每小时发送计数窗口(与策略 sms_max_send_per_hour 配合) */
    private static final Duration SEND_HOUR_WINDOW = Duration.ofHours(1);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IamSecurityPolicyManager securityPolicyManager;
    private final RealmProvider realmProvider;
    private final UserProvider userProvider;
    private final SmsChannelRouter channelRouter;
    private final RedisUtils redisUtils;
    private final IamRateLimiter rateLimiter;

    public SmsCodeService(IamSecurityPolicyManager securityPolicyManager, RealmProvider realmProvider,
                          UserProvider userProvider, SmsChannelRouter channelRouter,
                          RedisUtils redisUtils, IamRateLimiter rateLimiter) {
        this.securityPolicyManager = securityPolicyManager;
        this.realmProvider = realmProvider;
        this.userProvider = userProvider;
        this.channelRouter = channelRouter;
        this.redisUtils = redisUtils;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 发送短信验证码。
     * <p>手机号未注册时静默返回,响应与已发送一致。</p>
     *
     * @param realmKey 身份域标识
     * @param phone    手机号
     * @param ip       来源 IP(IP 维度限流)
     * @throws BusinessException 域未开启短信登录、号码格式非法、通道未配置或触发限流
     */
    public void sendCode(String realmKey, String phone, String ip) {
        // 1. 域开关:未开启直接拒绝,不让调用方以为码已上路
        LoginPolicy policy = loginPolicy(realmKey);
        if (!Boolean.TRUE.equals(policy.getSmsLoginEnabled())) {
            throw new BusinessException(ErrorCode.SMS_CHANNEL_NOT_CONFIGURED);
        }
        // 2. 格式校验(同时挡掉注入模板的脏值)
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "手机号格式不正确");
        }
        // 3. 通道:未配置或配置不完整 = 功能未启用,明确报错而非静默失败
        SmsChannel channel = channelRouter.resolve()
                .orElseThrow(() -> new BusinessException(ErrorCode.SMS_CHANNEL_NOT_CONFIGURED));
        // 4. 三重限流:IP 维度挡分布式轰炸,手机号维度挡单点连发。
        //    来源地址缺失时跳过 IP 维度:落到同一个 "unknown" 桶会让全站共用一个配额
        if (ip != null && !ip.isBlank()) {
            rateLimiter.check("sms-send-ip", ip, IP_SEND_LIMIT, IP_SEND_WINDOW);
        }
        rateLimiter.check("sms-resend:" + realmKey, phone, 1, RESEND_INTERVAL);
        checkHourlyQuota(realmKey, phone, policy.getSmsMaxSendPerHour());
        // 5. 只发给本域已注册且已验证的手机号;未注册静默返回(见类注释)
        Long realmId = realmIdOf(realmKey);
        Optional<UserModel> user = userProvider.findByPhone(realmId, phone);
        if (user.isEmpty()) {
            return;
        }
        // 6. 生成验证码并入库(TTL 由策略决定),同时清掉上一枚码的失败计数
        String code = generateCode();
        Duration ttl = ttlOf(policy);
        String codeKey = String.format(IamRedisKeyConstants.SMS_CODE, realmKey, phone);
        redisUtils.set(codeKey, code, ttl);
        redisUtils.delete(String.format(IamRedisKeyConstants.SMS_CODE_ATTEMPTS, realmKey, phone));
        // 7. 发送;失败必须删掉已入库的码,避免"码在库里但用户永远收不到"
        try {
            channel.send(phone, code, realmKey);
        } catch (RuntimeException e) {
            redisUtils.delete(codeKey);
            throw e;
        }
        // 8. 计入每小时配额(拒绝的请求也计数:偏保守,限流不错放)
        redisUtils.incrementWithTtl(
                String.format(IamRedisKeyConstants.SMS_SEND_HOUR_COUNT, realmKey, phone), SEND_HOUR_WINDOW);
    }

    /**
     * 校验短信验证码。
     * <p>校验通过即消费;校验失败累计尝试次数,达到域策略上限时作废该码,
     * 用户需重新取码。次数按手机号累计,取新码时清零。</p>
     *
     * @param realmKey 身份域标识
     * @param phone    手机号
     * @param code     用户输入
     * @return 是否通过
     */
    public boolean verifyCode(String realmKey, String phone, String code) {
        if (phone == null || code == null || code.isBlank()) {
            return false;
        }
        Integer configured = loginPolicy(realmKey).getSmsMaxVerifyAttempts();
        int maxAttempts = configured == null || configured <= 0 ? 5 : configured;
        String codeKey = String.format(IamRedisKeyConstants.SMS_CODE, realmKey, phone);
        String attemptsKey = String.format(IamRedisKeyConstants.SMS_CODE_ATTEMPTS, realmKey, phone);
        String expected = redisUtils.get(codeKey);
        if (expected == null) {
            // 码不存在、已过期或已被消费:对外同一个答案
            return false;
        }
        if (!constantTimeEquals(expected, code.trim())) {
            long attempts = redisUtils.increment(attemptsKey);
            if (attempts >= maxAttempts) {
                // 已达尝试上限:作废该码并清零计数,避免同一枚码被持续穷举
                redisUtils.delete(codeKey);
                redisUtils.delete(attemptsKey);
            }
            return false;
        }
        redisUtils.delete(codeKey);
        redisUtils.delete(attemptsKey);
        return true;
    }

    // ===== 内部实现 =====

    /**
     * 每小时配额校验:maxPerHour &lt;= 0 表示不限制。
     *
     * @param realmKey   身份域标识
     * @param phone      手机号
     * @param maxPerHour 每小时最大发送次数
     * @throws BusinessException 已超出每小时配额
     */
    private void checkHourlyQuota(String realmKey, String phone, int maxPerHour) {
        if (maxPerHour <= 0) {
            return;
        }
        String key = String.format(IamRedisKeyConstants.SMS_SEND_HOUR_COUNT, realmKey, phone);
        // 先看当前计数(未超限不占用配额),已满则拒绝
        String current = redisUtils.get(key);
        long used = current == null ? 0L : Long.parseLong(current);
        if (used >= maxPerHour) {
            throw new BusinessException(ErrorCode.SMS_SEND_TOO_FREQUENT);
        }
    }

    /**
     * 取域登录策略。
     *
     * @param realmKey 身份域标识
     * @return 域登录策略
     * @throws BusinessException 身份域不存在
     */
    private LoginPolicy loginPolicy(String realmKey) {
        return realmProvider.findByRealmKey(realmKey)
                .map(realm -> securityPolicyManager.getLoginPolicy(realm.id(), realm.realmKey()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "身份域不存在: " + realmKey));
    }

    /**
     * 取身份域主键。
     *
     * @param realmKey 身份域标识
     * @return 身份域主键
     * @throws BusinessException 身份域不存在
     */
    private Long realmIdOf(String realmKey) {
        return realmProvider.findByRealmKey(realmKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "身份域不存在: " + realmKey))
                .id();
    }

    /**
     * 取验证码有效期。
     *
     * @param policy 域登录策略
     * @return 有效期;策略未配置或非正数时为 300 秒
     */
    private Duration ttlOf(LoginPolicy policy) {
        Integer seconds = policy.getSmsCodeTtlSeconds();
        return Duration.ofSeconds(seconds == null || seconds <= 0 ? 300 : seconds);
    }

    /**
     * 生成 6 位数字验证码,前导零保留,长度固定。
     *
     * @return 验证码
     */
    private String generateCode() {
        int bound = (int) Math.pow(10, CODE_LENGTH);
        return String.format("%0" + CODE_LENGTH + "d", RANDOM.nextInt(bound));
    }

    /**
     * 定长时间比较,避免比较过程泄露时序信息。
     *
     * @param a 期望值
     * @param b 用户输入
     * @return 是否相等
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] left = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }
}
