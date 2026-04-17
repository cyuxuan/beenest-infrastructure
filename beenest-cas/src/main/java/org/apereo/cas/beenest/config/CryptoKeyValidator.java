package org.apereo.cas.beenest.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 关键加密密钥启动校验
 * <p>
 * 应用启动完成后检查 TGC/Webflow 等加密密钥是否仍为默认值，
 * 若在生产环境使用默认密钥则拒绝启动，防止安全事故。
 * <p>
 * 默认密钥仅用于本地开发，生产环境必须通过环境变量注入。
 */
@Slf4j
@Component
public class CryptoKeyValidator {

    private static final List<String> DEFAULT_KEYS = List.of(
            "changeme-changeme-changeme",
            "changeme-changeme-changeme-changeme-changeme-changeme-changeme",
            "changeme-changeme-changeme-changeme"
    );

    private final Environment env;

    public CryptoKeyValidator(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validateOnStartup() {
        // 非 prod 环境仅打印警告
        String[] activeProfiles = env.getActiveProfiles();
        boolean isProd = false;
        for (String profile : activeProfiles) {
            if ("pro".equals(profile) || "prod".equals(profile) || "production".equals(profile)) {
                isProd = true;
                break;
            }
        }

        List<String> violations = new ArrayList<>();
        checkKey(violations, "cas.tgc.crypto.encryption.key", "TGC 加密密钥");
        checkKey(violations, "cas.tgc.crypto.signing.key", "TGC 签名密钥");
        checkKey(violations, "beenest.mfa.encryption-key", "MFA 加密密钥");

        if (violations.isEmpty()) {
            LOGGER.info("加密密钥校验通过");
            return;
        }

        if (isProd) {
            // 生产环境：使用默认密钥，拒绝启动
            String msg = "生产环境检测到默认加密密钥，禁止启动！请设置以下环境变量：\n"
                    + String.join("\n", violations);
            LOGGER.error(msg);
            throw new IllegalStateException(msg);
        }

        // 非 prod 环境：打印警告
        LOGGER.warn("⚠️ 检测到默认加密密钥，请勿在生产环境使用：\n{}", String.join("\n", violations));
    }

    private void checkKey(List<String> violations, String property, String label) {
        String value = env.getProperty(property);
        if (value != null && DEFAULT_KEYS.contains(value)) {
            violations.add(String.format("  - %s (%s) 仍为默认值", property, label));
        }
    }
}
