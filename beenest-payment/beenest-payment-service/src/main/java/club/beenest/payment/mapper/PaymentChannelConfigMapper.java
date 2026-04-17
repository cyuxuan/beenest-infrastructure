package club.beenest.payment.mapper;

import club.beenest.payment.object.entity.PaymentChannelConfig;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 支付渠道配置数据访问接口
 */
@Mapper
public interface PaymentChannelConfigMapper {

    /**
     * 新增配置
     * @param config 配置实体
     * @return 影响行数
     */
    int insert(PaymentChannelConfig config);

    /**
     * 更新配置
     * @param config 配置实体
     * @return 影响行数
     */
    int update(PaymentChannelConfig config);

    /**
     * 根据渠道代码查询
     * @param channelCode 渠道代码
     * @return 配置实体
     */
    PaymentChannelConfig selectByChannelCode(String channelCode);

    /**
     * 查询所有配置
     * @return 配置列表
     */
    List<PaymentChannelConfig> selectAll();
}
