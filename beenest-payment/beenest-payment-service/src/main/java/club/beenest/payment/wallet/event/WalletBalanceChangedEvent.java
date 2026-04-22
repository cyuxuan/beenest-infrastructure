package club.beenest.payment.wallet.event;

import org.springframework.context.ApplicationEvent;

/**
 * 钱包余额变动事件
 * 余额操作成功后发布，由监听器检查异常模式
 *
 * @author System
 * @since 2026-04-01
 */
public class WalletBalanceChangedEvent extends ApplicationEvent {

    private final String walletNo;
    private final String customerNo;
    private final long beforeBalance;
    private final long afterBalance;
    private final long changeAmount;
    private final String transactionType;

    public WalletBalanceChangedEvent(Object source, String walletNo, String customerNo,
                                     long beforeBalance, long afterBalance, long changeAmount,
                                     String transactionType) {
        super(source);
        this.walletNo = walletNo;
        this.customerNo = customerNo;
        this.beforeBalance = beforeBalance;
        this.afterBalance = afterBalance;
        this.changeAmount = changeAmount;
        this.transactionType = transactionType;
    }

    public String getWalletNo() { return walletNo; }
    public String getCustomerNo() { return customerNo; }
    public long getBeforeBalance() { return beforeBalance; }
    public long getAfterBalance() { return afterBalance; }
    public long getChangeAmount() { return changeAmount; }
    public String getTransactionType() { return transactionType; }
}
