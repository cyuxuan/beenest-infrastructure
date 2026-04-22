package club.beenest.payment.wallet.mq;

import lombok.Data;

import java.io.Serializable;

/**
 * 余额变动消息
 * 支付中台 → 主业务服务
 */
@Data
public class BalanceChangedMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户编号 */
    private String customerNo;

    /** 钱包编号 */
    private String walletNo;

    /** 变动前余额（分） */
    private Long beforeBalanceFen;

    /** 变动后余额（分） */
    private Long afterBalanceFen;

    /** 变动金额（分），正数增加、负数减少 */
    private Long changeAmountFen;

    /** 交易类型 */
    private String transactionType;

    /** 业务类型（多租户隔离） */
    private String bizType;

    /** 消息唯一ID（用于幂等） */
    private String messageId;

    /** 消息签名（HMAC-SHA256，防伪造/篡改） */
    private String sign;
}
