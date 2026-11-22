package club.beenest.iam.authz.security;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.common.web.IamRateLimiter;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 图形验证码服务。
 * <p>生成 4 位字符验证码(PNG,Base64 内联返回),答案以小写形式存 Redis,有效期 5 分钟;
 * 登录失败达到域策略阈值后要求校验,校验一次性消费。</p>
 */
@Service
public class CaptchaService {

    /** 验证码长度 */
    private static final int CODE_LENGTH = 4;
    /** 验证码字符表(去掉易混淆字符) */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    /** 验证码有效期 */
    private static final Duration TTL = Duration.ofMinutes(5);
    /** 图片尺寸 */
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RedisUtils redisUtils;
    /** 通用限流:未登录可达的接口共用同一份计数口径 */
    private final IamRateLimiter rateLimiter;

    public CaptchaService(RedisUtils redisUtils, IamRateLimiter rateLimiter) {
        this.redisUtils = redisUtils;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 登录页探测限流。
     * <p>"是否需要验证码"在未登录状态下可达,按来源 IP 限速,超出直接拒绝。</p>
     *
     * @param scene  限流场景
     * @param ip     来源 IP
     * @param limit  窗口内最大次数
     * @param window 窗口时长
     * @throws BusinessException 超过窗口内最大次数
     */
    public void checkRateLimit(String scene, String ip, int limit, Duration window) {
        rateLimiter.check(scene, ip, limit, window);
    }

    /**
     * 验证码图片。
     *
     * @param captchaId   验证码标识,校验时回传
     * @param imageBase64 PNG 图片的 Base64 文本,含 data URI 前缀
     */
    public record CaptchaImage(String captchaId, String imageBase64) {
    }

    /**
     * 生成验证码。
     *
     * @return 验证码标识与图片
     */
    public CaptchaImage generate() {
        String captchaId = java.util.UUID.randomUUID().toString().replace("-", "");
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        // 答案统一小写存放,校验时忽略大小写
        redisUtils.set(String.format(IamRedisKeyConstants.CAPTCHA, captchaId),
                code.toString().toLowerCase(java.util.Locale.ROOT), TTL);
        return new CaptchaImage(captchaId, toBase64Png(render(code.toString())));
    }

    /**
     * 校验验证码;无论成功失败都一次性消费,防止重放。
     *
     * @param captchaId 验证码标识
     * @param answer    用户输入
     * @return 是否通过
     */
    public boolean verify(String captchaId, String answer) {
        if (captchaId == null || captchaId.isBlank() || answer == null || answer.isBlank()) {
            return false;
        }
        String key = String.format(IamRedisKeyConstants.CAPTCHA, captchaId);
        // 原子取走:并发请求里只有一个能拿到答案,拿到即失效
        String expected = redisUtils.getAndDelete(key);
        if (expected == null) {
            return false;
        }
        return expected.equalsIgnoreCase(answer.trim());
    }

    /**
     * 强制校验验证码。
     *
     * @param captchaId 验证码标识
     * @param answer    用户输入
     * @throws BusinessException 验证码无效或已过期
     */
    public void verifyOrThrow(String captchaId, String answer) {
        if (!verify(captchaId, answer)) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID);
        }
    }

    /**
     * 绘制验证码图片。
     *
     * @param code 验证码文本
     * @return 图片
     */
    private BufferedImage render(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // 1. 背景
            g.setColor(new Color(245, 246, 250));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            // 2. 干扰线
            for (int i = 0; i < 4; i++) {
                g.setColor(new Color(RANDOM.nextInt(200), RANDOM.nextInt(200), RANDOM.nextInt(200)));
                g.setStroke(new BasicStroke(1.5f));
                int x1 = RANDOM.nextInt(WIDTH);
                int y1 = RANDOM.nextInt(HEIGHT);
                int x2 = RANDOM.nextInt(WIDTH);
                int y2 = RANDOM.nextInt(HEIGHT);
                g.drawLine(x1, y1, x2, y2);
            }
            // 3. 字符(随机颜色/旋转/偏移)
            for (int i = 0; i < code.length(); i++) {
                AffineTransform original = g.getTransform();
                g.setColor(new Color(20 + RANDOM.nextInt(120), 20 + RANDOM.nextInt(120), 20 + RANDOM.nextInt(120)));
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
                AffineTransform transform = new AffineTransform();
                transform.rotate((RANDOM.nextDouble() - 0.5) * 0.5, 18 + i * 28, HEIGHT / 2.0);
                g.setTransform(transform);
                g.drawString(String.valueOf(code.charAt(i)), 14 + i * 26, 24 + RANDOM.nextInt(6) - 3);
                g.setTransform(original);
            }
            // 4. 噪点
            for (int i = 0; i < 60; i++) {
                g.setColor(new Color(RANDOM.nextInt(255), RANDOM.nextInt(255), RANDOM.nextInt(255)));
                g.fillRect(RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT), 1, 1);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * PNG 转 Base64 data URI。
     *
     * @param image 图片
     * @return 含 data URI 前缀的 Base64 串
     * @throws IllegalStateException 图片编码失败
     */
    private String toBase64Png(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("验证码图片生成失败", e);
        }
    }
}
