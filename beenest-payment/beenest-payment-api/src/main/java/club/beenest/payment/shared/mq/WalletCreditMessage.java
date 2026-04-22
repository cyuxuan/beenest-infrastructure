package club.beenest.payment.shared.mq;

import lombok.Data;

/**
 * 钱包入账消息 — 通用钱包余额增加指令
 *
 * <p>由业务系统发布，支付中台消费。业务系统负责计算金额和决定入账对象，
 * 支付中台只执行"给谁加多少钱"的操作，不感知任何业务语义。</p>
 *
 * <h3>设计原则：</h3>
 * <ul>
 *   <li>所有金额使用「分」为单位（Long），与支付中台其他消息保持一致</li>
 *   <li>transactionType 必须是 {@link club.beenest.payment.wallet.enums.WalletTransactionType} 枚举值</li>
 *   <li>referenceNo 同时作为幂等键，防止重复入账</li>
 *   <li>一条消息对应一笔入账操作</li>
 * </ul>
 *
 * @author System
 * @since 2026-04-21
 */
@Data
public class WalletCreditMessage {

    /** 入账用户编号（必填） */
    private String customerNo;

    /** 业务类型（可选，默认 DRONE_ORDER） */
    private String bizType;

    /** 入账金额，单位：分（必填，必须 > 0） */
    private Long amountFen;

    /** 交易类型（必填，WalletTransactionType 枚举值） */
    private String transactionType;

    /** 交易描述（必填） */
    private String description;

    /** 幂等键 / 关联单号（必填，基于此字段防重复入账） */
    private String referenceNo;

    /** 消息唯一 ID */
    private String messageId;

    /** HMAC-SHA256 签名 */
    private String sign;
}
