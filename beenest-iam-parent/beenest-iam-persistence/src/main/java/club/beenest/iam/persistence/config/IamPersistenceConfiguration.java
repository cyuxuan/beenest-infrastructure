package club.beenest.iam.persistence.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;

/**
 * 持久层配置:扫描 MyBatis Mapper 接口,并提供全局口令编码器。
 */
@Configuration
@MapperScan("club.beenest.iam.persistence.mapper")
public class IamPersistenceConfiguration {

    /** 新口令使用的算法标识,与密文前缀一致 */
    private static final String ARGON2_ID = "argon2";

    /**
     * 口令编码器:新口令用 Argon2id,历史 bcrypt 密文继续可校验。
     * <p>Argon2id 的内存硬度让 GPU 离线爆破的成本远高于 bcrypt,是当前口令存储的推荐算法。
     * 密文自带算法前缀,存量 {@code {bcrypt}} 记录无需迁移即可继续登录,
     * 用户下次改密时自然升级为 Argon2id。</p>
     * <p>不注册 {@code {noop}}:该前缀允许明文口令通过校验,一旦有历史数据残留
     * 就等于口令校验形同虚设。</p>
     *
     * @return 委托式口令编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put(ARGON2_ID, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
        encoders.put("bcrypt", new BCryptPasswordEncoder());
        DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder(ARGON2_ID, encoders);
        // 无前缀的历史密文按 bcrypt 校验(早期数据可能存在),其余未知前缀一律拒绝
        encoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return encoder;
    }
}
