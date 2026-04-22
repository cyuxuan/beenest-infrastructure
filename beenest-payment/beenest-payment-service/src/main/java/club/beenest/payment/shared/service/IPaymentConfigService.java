package club.beenest.payment.shared.service;

import club.beenest.payment.shared.domain.entity.PaymentChannelConfig;

import java.util.List;

/**
 * 支付配置服务接口
 */
public interface IPaymentConfigService {

    /**
     * 获取所有配置
     * @return 配置列表
     */
    List<PaymentChannelConfig> getAllConfigs();

    /**
     * 更新配置
     * @param config 配置信息
     */
    void updateConfig(PaymentChannelConfig config);

    /**
     * 根据渠道代码获取配置
     * @param channelCode 渠道代码
     * @return 配置信息
     */
    PaymentChannelConfig getConfig(String channelCode);
}
