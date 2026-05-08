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
 * 应用启动完成后检查所有加密密钥是否仍为默认值或占位符，
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
            "changeme-changeme-changeme-changeme",
            "CHANGE_ME"
    );

    /**
     * 需要校验的加密密钥属性列表
     */
    private static final List<KeyCheck> KEY_PROPERTIES = List.of(
            new KeyCheck("cas.tgc.crypto.encryption.key", "TGC 加密密钥"),
            new KeyCheck("cas.tgc.crypto.signing.key", "TGC 签名密钥"),
            new KeyCheck("cas.webflow.crypto.encryption.key", "Webflow 加密密钥"),
            new KeyCheck("cas.webflow.crypto.signing.key", "Webflow 签名密钥"),
            new KeyCheck("cas.ticket.registry.core.crypto.encryption.key", "Ticket 加密密钥"),
            new KeyCheck("cas.ticket.registry.core.crypto.signing.key", "Ticket 签名密钥"),
            new KeyCheck("cas.account-registration.core.crypto.encryption.key", "账户注册加密密钥"),
            new KeyCheck("cas.account-registration.core.crypto.signing.key", "账户注册签名密钥"),
            new KeyCheck("cas.authn.mfa.gauth.crypto.encryption.key", "MFA GA 加密密钥"),
            new KeyCheck("cas.authn.mfa.gauth.crypto.signing.key", "MFA GA 签名密钥"),
            new KeyCheck("cas.authn.mfa.gauth.core.scratch-codes.encryption.key", "MFA GA 备用码加密密钥"),
            new KeyCheck("cas.authn.mfa.web-authn.crypto.encryption.key", "MFA WebAuthn 加密密钥"),
            new KeyCheck("cas.authn.mfa.web-authn.crypto.signing.key", "MFA WebAuthn 签名密钥"),
            new KeyCheck("cas.authn.mfa.trusted.crypto.encryption.key", "MFA 信任设备加密密钥"),
            new KeyCheck("cas.authn.mfa.trusted.crypto.signing.key", "MFA 信任设备签名密钥"),
            new KeyCheck("beenest.mfa.encryption-key", "Beenest MFA 加密密钥"),
            new KeyCheck("beenest.token.validation-secret", "Token 验证密钥")
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
        for (KeyCheck check : KEY_PROPERTIES) {
            checkKey(violations, check.property(), check.label());
        }

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

    private record KeyCheck(String property, String label) {}
}
