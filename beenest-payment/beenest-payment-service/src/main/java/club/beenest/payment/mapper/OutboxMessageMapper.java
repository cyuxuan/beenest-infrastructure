package club.beenest.payment.mapper;

import club.beenest.payment.object.entity.OutboxMessage;
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
}
