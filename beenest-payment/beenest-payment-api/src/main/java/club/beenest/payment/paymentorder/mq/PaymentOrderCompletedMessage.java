package club.beenest.payment.paymentorder.mq;

import lombok.Data;

import java.io.Serializable;

/**
 * 支付订单完成消息
 * 支付中台 → 主业务服务
 */
@Data
public class PaymentOrderCompletedMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 支付订单号 */
    private String orderNo;

    /** 关联的业务订单号 */
    private String businessOrderNo;

    /** 用户编号 */
    private String customerNo;

    /** 支付金额（分） */
    private Long amountFen;

    /** 支付平台（WECHAT/ALIPAY/DOUYIN） */
    private String platform;

    /** 支付时间 */
    private String paidAt;

    /** 消息唯一ID（用于幂等） */
    private String messageId;

    /** 业务类型（多租户隔离） */
    private String bizType;

    /** 消息签名（HMAC-SHA256，防伪造/篡改） */
    private String sign;
}
