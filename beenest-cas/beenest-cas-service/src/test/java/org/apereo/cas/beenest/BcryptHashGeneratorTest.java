package org.apereo.cas.beenest;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt 密码哈希生成工具测试类。
 * 用于生成和验证用户密码的 bcrypt hash。
 */
public class BcryptHashGeneratorTest {

    @Test
    void generateAndVerifyHash() {
        // 与 application.yml 中 cas.authn.pm.jdbc.password-encoder.strength=12 保持一致
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        String rawPassword = "admin123";
        String hash = encoder.encode(rawPassword);

        System.out.println("========================================");
        System.out.println("Password: " + rawPassword);
        System.out.println("Hash:     " + hash);
        System.out.println("Match:    " + encoder.matches(rawPassword, hash));
        System.out.println("========================================");
    }
}
