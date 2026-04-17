package club.beenest.payment.mq.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 退款完成消息
 * 支付中台 → 主业务服务
 */
@Data
public class RefundCompletedMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 退款单号 */
    private String refundNo;

    /** 原支付订单号 */
    private String orderNo;

    /** 关联的业务订单号 */
    private String businessOrderNo;

    /** 用户编号 */
    private String customerNo;

    /** 退款金额（分） */
    private Long refundAmountFen;

    /** 退款状态（SUCCESS/FAILED） */
    private String status;

    /** 业务类型（多租户隔离） */
    private String bizType;

    /** 消息唯一ID（用于幂等） */
    private String messageId;

    /** 消息签名（HMAC-SHA256，防伪造/篡改） */
    private String sign;
}
