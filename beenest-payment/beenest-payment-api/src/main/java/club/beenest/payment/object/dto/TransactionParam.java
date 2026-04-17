package club.beenest.payment.object.dto;

import lombok.Data;

/**
 * 交易参数封装对象
 * 用于封装创建交易记录时所需的参数
 */
@Data
public class TransactionParam {
    private String transactionNo;
    private String walletNo;
    private String customerNo;
    private String transactionType;
    private Long amount;
    private Long beforeBalance;
    private Long afterBalance;
    private String description;
    private String referenceNo;
    private String referenceType;
    private String status;
}
