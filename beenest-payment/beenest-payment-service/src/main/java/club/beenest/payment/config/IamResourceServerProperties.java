package club.beenest.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * IAM 资源服务配置。
 * <p>
 * 支付服务不再内置 CAS 客户端，而是作为标准 OAuth2 资源服务接入 IAM：
 * IAM 负责"你是谁、你能进哪些应用、你持有哪些权限码"，支付服务只负责校验 IAM 签发的
 * 访问令牌，并在本地维护钱包档案。
 *
 * <p>协议端点（issuer / JWKS）仍由 Spring Boot 标准配置项
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} 提供，本类只承载
 * IAM 特有的校验与映射约定。</p>
 *
 * <p>用户身份（钱包 {@code customer_no}）取 IAM 令牌中的<b>手机号</b> {@code phone_number}，
 * 不提供配置项：手机号是 IAM 文档指定的跨渠道归并锚点，同一自然人从微信、支付宝、后台建号
 * 登录都收敛到同一个钱包；{@code uid} 只能标识单个 IAM 账号，{@code sub} 还会随主体策略变化。</p>
 *
 * @since 2026-10-29
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "iam.resource-server")
public class IamResourceServerProperties {

    /**
     * 是否启用 IAM 资源服务安全链。
     * <p>关闭后支付服务不注册安全过滤链，仅用于本地排障，生产环境必须保持开启。</p>
     */
    private boolean enabled = true;

    /**
     * 期望的访问令牌受众（aud）。
     * <p>IAM 的访问令牌受众由「应用门户 → 令牌受众」决定，未登记时为
     * {@code urn:iam:app:{appCode}}。同一个后端的多个入口（Web / 小程序）应登记同一个受众。</p>
     */
    private String audience;

    /**
     * 是否强制要求 {@code token_kind=APPLICATION}。
     * <p>IAM 文档要求资源服务器拒绝平台令牌（PLATFORM）与机器令牌（MACHINE）。
     * 接入初期若客户端尚未绑定应用门户，可临时关闭；正式环境建议开启。</p>
     */
    private boolean requireApplicationToken = false;

    /**
     * 首次访问时是否自动开通钱包。
     * <p>替代原 CAS 访问控制 SPI 的"授权即建户"语义：用户首次持有效令牌访问 C 端接口时，
     * 自动为其创建钱包档案。关闭后无档案的用户直接 403。</p>
     */
    private boolean autoProvisionWallet = true;

    /**
     * 自动开通钱包时使用的业务类型。
     */
    private String walletBizType = "DRONE_ORDER";

    /**
     * 钱包状态非 ACTIVE 时是否直接拒绝访问。
     * <p>替代原 CAS 访问控制中的"本地用户被禁用即拒绝"逻辑。</p>
     */
    private boolean rejectInactiveWallet = true;
}
