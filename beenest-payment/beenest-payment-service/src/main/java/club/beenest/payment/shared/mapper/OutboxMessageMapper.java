package club.beenest.payment.shared.mapper;

import club.beenest.payment.shared.domain.entity.OutboxMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxMessageMapper {

    int insert(OutboxMessage outboxMessage);

    int updateStatus(@Param("messageId") String messageId,
                     @Param("status") String status,
                     @Param("errorMessage") String errorMessage);

    int incrementRetry(@Param("messageId") String messageId,
                       @Param("nextRetryTime") LocalDateTime nextRetryTime,
                       @Param("errorMessage") String errorMessage);

    OutboxMessage selectByMessageId(@Param("messageId") String messageId);

    List<OutboxMessage> selectPendingForRetry(@Param("limit") int limit);

    /**
     * 检查指定 routingKey 和订单号是否已有成功发送的 Outbox 记录
     *
     * <p>用于补偿调度器判断业务系统是否已确认收到支付成功通知。</p>
     *
     * @param routingKey 路由键（如 payment.order.completed）
     * @param orderNo 支付订单号（从 payload 中匹配）
     * @return 是否存在成功发送记录
     */
    boolean existsSentByRoutingKeyAndOrderNo(@Param("routingKey") String routingKey,
                                             @Param("orderNo") String orderNo);
}
