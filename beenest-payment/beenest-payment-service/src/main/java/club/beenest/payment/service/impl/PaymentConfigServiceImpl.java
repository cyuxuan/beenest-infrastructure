package club.beenest.payment.service.impl;

import club.beenest.payment.mapper.PaymentChannelConfigMapper;
import club.beenest.payment.object.entity.PaymentChannelConfig;
import club.beenest.payment.service.IPaymentConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 支付配置服务实现类
 *
 * @author System
 * @since 2026-02-11
 */
@Service
public class PaymentConfigServiceImpl implements IPaymentConfigService {

    @Autowired
    private PaymentChannelConfigMapper paymentChannelConfigMapper;

    @Override
    public List<PaymentChannelConfig> getAllConfigs() {
        return paymentChannelConfigMapper.selectAll();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(PaymentChannelConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("配置信息不能为空");
        }
        if (config.getId() != null) {
            paymentChannelConfigMapper.update(config);
        } else {
            // Check if exists
            PaymentChannelConfig existing = paymentChannelConfigMapper.selectByChannelCode(config.getChannelCode());
            if (existing != null) {
                config.setId(existing.getId());
                paymentChannelConfigMapper.update(config);
            } else {
                paymentChannelConfigMapper.insert(config);
            }
        }
    }

    @Override
    public PaymentChannelConfig getConfig(String channelCode) {
        return paymentChannelConfigMapper.selectByChannelCode(channelCode);
    }
}
