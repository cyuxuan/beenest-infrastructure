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
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        // 符合策略: 12-128位，含大小写+数字+特殊字符
        String rawPassword = "admin123";
        String hash = encoder.encode(rawPassword);

        System.out.println("========================================");
        System.out.println("Password: " + rawPassword);
        System.out.println("Hash:     " + hash);
        System.out.println("Match:    " + encoder.matches(rawPassword, hash));
        System.out.println("========================================");
    }
}
