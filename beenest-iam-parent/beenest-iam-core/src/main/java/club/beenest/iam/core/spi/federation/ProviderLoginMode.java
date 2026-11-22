package club.beenest.iam.core.spi.federation;

/**
 * 身份提供方的登录形态。
 * <p>由提供方声明,平台据此决定入口:跳转类出现在登录页按钮中,
 * 凭据类出现在客户端直传的登录接口中。</p>
 */
public enum ProviderLoginMode {

    /**
     * 浏览器跳转:平台把用户送到外部提供方,再凭回调换回身份。
     * <p>适用于提供授权地址的外部身份源。</p>
     */
    REDIRECT,

    /**
     * 凭据直传:客户端把第三方凭据直接上送平台,平台据此换回身份。
     * <p>适用于没有浏览器跳转通道的场景,如小程序 wx.login() 得到的 code。</p>
     */
    CREDENTIAL
}
