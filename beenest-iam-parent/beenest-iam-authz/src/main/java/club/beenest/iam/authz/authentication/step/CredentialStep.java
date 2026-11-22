package club.beenest.iam.authz.authentication.step;

import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.authentication.LoginFailureCounter;
import club.beenest.iam.authz.ldap.LdapUserAuthenticator;
import club.beenest.iam.common.i18n.IamMessages;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.UserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

/**
 * 凭据校验步骤。
 * <p>按"本地口令优先、目录兜底"的顺序校验第一因素:先按域加载本地用户;
 * 本地账号不存在、或存在但没有口令凭证(JIT 创建的三方/目录账号)时,
 * 域启用联合则到目录 bind 认证,成功即复用/创建本地用户。</p>
 * <p>账号不存在时执行等强度陪跑哈希,抹平与口令错误之间的耗时差;
 * 所有对外失败统一为"账号或密码错误",真实原因写入审计。</p>
 */
public class CredentialStep implements AuthenticationStep {

    private static final Logger log = LoggerFactory.getLogger(CredentialStep.class);

    /**
     * 账号不存在时用于陪跑的一次性哈希(throwaway,不对应任何真实口令)。
     * <p>用户不存在时若不执行哈希运算直接返回,响应时间会明显短于口令错误,形成可测量的
     * 时序侧信道,攻击者据此枚举有效账号。</p>
     * <p>必须带 {@code {bcrypt}} 前缀:平台用 DelegatingPasswordEncoder 按前缀分派算法,
     * 无前缀的裸哈希会被判为无法识别的编码而抛异常,该异常冒泡到全局处理器即返回 500,
     * 使账号不存在与口令错误给出不同状态码,反而提供一条更省事的枚举通道。</p>
     */
    private static final String DUMMY_PASSWORD_HASH =
            "{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserProvider userProvider;
    private final LdapUserAuthenticator ldapUserAuthenticator;
    private final PasswordEncoder passwordEncoder;
    private final LoginFailureCounter loginFailureCounter;
    private final LoginFailureRecorder failureRecorder;

    public CredentialStep(UserProvider userProvider, LdapUserAuthenticator ldapUserAuthenticator,
                          PasswordEncoder passwordEncoder, LoginFailureCounter loginFailureCounter,
                          LoginFailureRecorder failureRecorder) {
        this.userProvider = userProvider;
        this.ldapUserAuthenticator = ldapUserAuthenticator;
        this.passwordEncoder = passwordEncoder;
        this.loginFailureCounter = loginFailureCounter;
        this.failureRecorder = failureRecorder;
    }

    @Override
    public Stage stage() {
        return Stage.FIRST_FACTOR;
    }

    @Override
    public String name() {
        return "credential";
    }

    @Override
    public int order() {
        return 10;
    }

    /**
     * 校验第一因素凭据。
     * <p>目录已校验过口令的用户不再走本地口令校验:JIT 账号本就没有 PASSWORD 凭证行,
     * 重复校验必然失败,会让二次登录永远失败。</p>
     *
     * @param context 认证上下文,执行后写入用户、登录方式与"凭据已校验"标记
     * @throws AuthenticationException 账号不存在或凭据错误时抛出
     */
    @Override
    public void execute(AuthenticationContext context) throws AuthenticationException {
        RealmModel realm = context.getRealm();
        String username = context.getUsername();
        String password = context.getCredentials();
        // 1. 按域加载本地用户
        UserModel user = userProvider.findByUsername(realm.id(), username).orElse(null);
        boolean verified = false;
        String loginType = LoginMethod.PASSWORD.code();
        // 2. 目录兜底:本地无账号,或本地账号没有口令凭证
        if (user == null || !userProvider.hasLocalPassword(user.id())) {
            Optional<UserModel> ldapUser = ldapUserAuthenticator.authenticate(realm, username, password);
            if (ldapUser.isPresent()) {
                user = ldapUser.get();
                verified = true;
                // 目录兜底认证与本地口令是两回事:准入白名单要能区分二者
                loginType = LoginMethod.LDAP.code();
            }
        }
        // 3. 账号不存在:陪跑哈希后按统一出口失败
        if (user == null) {
            runDummyHash(password);
            // 不存在的账号不计入账号失败计数:攻击者用随机用户名即可灌满 Redis 计数键;
            // 但仍计入来源 IP 计数,随机用户名正是撞库与喷洒的典型特征
            loginFailureCounter.onIpFailure(realm.realmKey(), context.getClientIp(), context.getLoginPolicy());
            failureRecorder.record(context, null, "账号不存在");
            throw new BadCredentialsException(IamMessages.message(ErrorCode.BAD_CREDENTIALS));
        }
        // 4. 本地口令校验(目录已校验过的跳过)
        if (!verified && !userProvider.verifyPassword(user.id(), password)) {
            loginFailureCounter.onFailure(realm.realmKey(), username, context.getLoginPolicy());
            loginFailureCounter.onIpFailure(realm.realmKey(), context.getClientIp(), context.getLoginPolicy());
            failureRecorder.record(context, user.id(), "账号或口令错误");
            throw new BadCredentialsException(IamMessages.message(ErrorCode.BAD_CREDENTIALS));
        }
        context.setUser(user);
        context.setLoginType(loginType);
        context.setCredentialVerified(true);
    }

    /**
     * 执行一次等强度哈希,抹平"账号不存在"与"口令错误"的耗时差。
     * <p>陪跑失败不改变结论:账号不存在一律按账号或密码错误处理。放任异常冒到全局处理器
     * 会变成 500,把账号不存在单独标出,反而给攻击者一条比时序更省事的枚举通道。</p>
     *
     * @param password 提交的口令
     */
    private void runDummyHash(String password) {
        try {
            passwordEncoder.matches(password, DUMMY_PASSWORD_HASH);
        } catch (RuntimeException e) {
            log.debug("陪跑哈希执行失败(不影响登录结论): {}", e.getMessage());
        }
    }
}
