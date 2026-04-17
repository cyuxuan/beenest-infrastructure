package club.beenest.payment.mapper;

import club.beenest.payment.object.entity.PaymentEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 支付事件Mapper
 */
@Mapper
public interface PaymentEventMapper {

    /**
     * 新增支付事件
     * @param event 支付事件实体
     * @return 影响行数
     */
    int insert(PaymentEvent event);

    /**
     * 更新支付事件
     * @param event 支付事件实体
     * @return 影响行数
     */
    int update(PaymentEvent event);

    /**
     * 查询支付事件列表
     * @param orderNo 订单号
     * @param eventType 事件类型
     * @param channel 渠道
     * @return 支付事件列表
     */
    List<PaymentEvent> selectByQuery(@Param("orderNo") String orderNo, @Param("eventType") String eventType, @Param("channel") String channel);

    /**
     * 根据ID查询支付事件
     * @param id 事件ID
     * @return 支付事件实体
     */
    PaymentEvent selectById(Long id);
}
