package club.beenest.iam.core.spi.sms;

/**
 * 短信通道 SPI。
 * <p>短信验证码登录通过通道下发短信;更换短信服务商时实现本接口并配置
 * {@code iam.sms.channel}。发送失败时抛出异常,由调用方删除已生成的验证码,
 * 通道不得吞掉错误。</p>
 */
public interface SmsChannel {

    /**
     * 通道标识。
     *
     * @return 通道标识,与配置项 {@code iam.sms.channel} 对应,如 {@code aliyun}
     */
    String channelId();

    /**
     * 发送一条短信。
     *
     * @param phone    手机号(已按大陆格式校验)
     * @param code     验证码明文(通道负责拼模板)
     * @param realmKey 身份域标识,多租户场景用于通道侧区分与日志
     * @throws club.beenest.iam.common.exception.BusinessException 发送失败,消息面向运维
     */
    void send(String phone, String code, String realmKey);

    /**
     * 通道配置是否完整到可以发送。
     * <p>路由器据此过滤,配置不完整的通道不参与发送。</p>
     *
     * @return 配置完整时为 true
     */
    default boolean isConfigured() {
        return true;
    }
}
