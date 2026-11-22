package club.beenest.iam.core.spi.provider;

import club.beenest.iam.core.model.UserModel;

import java.util.Map;
import java.util.Optional;

/**
 * 用户存储 SPI。
 * <p>由持久层实现;认证流程(UserDetailsService)与令牌定制经此读取用户。</p>
 */
public interface UserProvider {

    /**
     * 按登录名与身份域查找用户。
     *
     * @param realmId  所属身份域
     * @param username 登录名
     * @return 用户;不存在时为空
     */
    Optional<UserModel> findByUsername(Long realmId, String username);

    /**
     * 按身份域与已验证手机号查找用户。
     * <p>短信验证码登录用;只匹配 {@code phone_verified = TRUE} 的用户。</p>
     *
     * @param realmId 所属身份域
     * @param phone   手机号
     * @return 用户;不存在或手机号未验证时为空
     */
    Optional<UserModel> findByPhone(Long realmId, String phone);

    /**
     * 按身份域与邮箱查找用户。
     * <p>找回口令、邮箱验证时使用;不要求邮箱已验证。</p>
     *
     * @param realmId 所属身份域
     * @param email   邮箱
     * @return 用户;不存在时为空
     */
    Optional<UserModel> findByEmail(Long realmId, String email);

    /**
     * 按主键查找用户。
     *
     * @param id 用户主键
     * @return 用户;不存在时为空
     */
    Optional<UserModel> findById(Long id);

    /**
     * 校验用户密码。
     * <p>按 PASSWORD 凭证行比对,编码方式为 DelegatingPasswordEncoder。</p>
     *
     * @param userId      用户主键
     * @param rawPassword 明文口令
     * @return 校验通过时为 true
     */
    boolean verifyPassword(Long userId, String rawPassword);

    /**
     * 用户是否持有本地口令凭证。
     * <p>JIT 创建的账号(三方登录/LDAP 联合)没有 PASSWORD 凭证行;
     * 认证链据此判断是否继续走外部认证源。</p>
     *
     * @param userId 用户主键
     * @return 存在 PASSWORD 凭证行时为 true
     */
    boolean hasLocalPassword(Long userId);

    /**
     * 递增用户令牌版本号。
     * <p>递增后该用户已签发的全部令牌在验签时失效。</p>
     *
     * @param userId 用户主键
     */
    void incrementTokenVersion(Long userId);

    /**
     * 创建本地联合用户(EXTERNAL 类型,无密码凭证)。
     *
     * @param realmId       所属身份域
     * @param username      登录名
     * @param email         邮箱
     * @param nickname      昵称
     * @param verifiedPhone 提供方担保已验证的手机号;非空时同时置手机号已验证标记
     * @param attributes    按提供方映射规则搬运的扩展属性;为空表示不写扩展属性
     * @return 创建后的用户
     */
    UserModel createFederatedUser(Long realmId, String username, String email, String nickname,
                                   String verifiedPhone, Map<String, String> attributes);

    /**
     * 给已有账号补写已验证手机号。
     * <p>外部身份先绑定、后取得手机号时使用;手机号为提供方担保的已验证值。</p>
     *
     * @param userId 用户主键
     * @param phone  已验证手机号
     */
    void updateVerifiedPhone(Long userId, String phone);
}
