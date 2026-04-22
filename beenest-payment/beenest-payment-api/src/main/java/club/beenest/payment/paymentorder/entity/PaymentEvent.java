package club.beenest.payment.paymentorder.entity;

import club.beenest.payment.paymentorder.enums.PaymentEventStatus;
import club.beenest.payment.paymentorder.enums.PaymentEventType;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 支付事件实体
 *
 * @author System
 * @since 2026-02-11
 */
@Data
public class PaymentEvent {
    /**
     * 主键ID
     */
    private Long id;

    /**
     * 事件编号
     */
    private String eventNo;

    /**
     * 关联订单号
     */
    private String orderNo;

    /**
     * 事件类型 (CALLBACK, NOTIFY, REFUND_NOTIFY)
     */
    private String eventType;

    /**
     * 支付渠道
     */
    private String channel;

    /**
     * 状态 (PENDING, SUCCESS, FAILED)
     */
    private String status;

    /**
     * 请求内容
     */
    private String requestContent;

    /**
     * 响应内容
     */
    private String responseContent;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 获取事件类型枚举
     */
    public PaymentEventType getEventTypeEnum() {
        return PaymentEventType.getByCode(eventType);
    }

    /**
     * 设置事件类型枚举
     */
    public void setEventTypeEnum(PaymentEventType eventTypeEnum) {
        if (eventTypeEnum != null) {
            this.eventType = eventTypeEnum.getCode();
        }
    }

    /**
     * 获取状态枚举
     */
    public PaymentEventStatus getStatusEnum() {
        return PaymentEventStatus.getByCode(status);
    }

    /**
     * 设置状态枚举
     */
    public void setStatusEnum(PaymentEventStatus statusEnum) {
        if (statusEnum != null) {
            this.status = statusEnum.getCode();
        }
    }
}
