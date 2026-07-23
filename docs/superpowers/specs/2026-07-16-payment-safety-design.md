# 支付服务安全整改设计

> 日期：2026-07-16
> 范围：beenest-payment 全链路支付/退款安全性整改
> 原则：支付成功扣款后正确处理订单，退单成功后正确退款，极端场景（down机）不丢钱

---

## 一、问题清单

| # | 级别 | 问题 | 极端场景后果 |
|---|------|------|-------------|
| 1 | P0 | 充值退款先扣余额再调第三方，down机时余额已扣但退款未发起 | 用户余额没了，钱也没退回 |
| 2 | P0 | 支付回调 @Transactional 内包含第三方网络调用 | 长时间持锁/连接池耗尽；验签后down机导致"已扣款未记录" |
| 3 | P0 | 退款流程 @Transactional 内调第三方退款 | 第三方退款成功但down机，Refund状态未更新，PENDING状态无补偿 |
| 4 | P0 | 退款回调签名验证失败返回 true | 伪造退款回调被静默接受 |
| 5 | P0 | 过期标记查询第三方失败时仍标记过期（Scheduler与Listener不一致） | 用户已付款但被标EXPIRED |
| 6 | P1 | 退款回调 Refund 查询无行锁 | 并发回调重复处理 |
| 7 | P1 | PENDING状态退款单无补偿机制 | 退款单永久卡在PENDING |
| 8 | P1 | 余额变动MQ用afterCommit发送，不走Outbox | MQ不可用时消息丢失 |
| 9 | P1 | 退款完成消息缺少退款金额 | 消费端需额外查询 |
| 10 | P2 | 死信队列无自动重试机制 | 消息永久滞留DLQ |
| 11 | P2 | 退款状态同步用内存ScheduledExecutorService | down机后丢失 |
| 12 | P2 | 补偿同步 syncPaymentOrderFromQuery 缺少事务 | 订单已支付但余额未到账 |

---

## 二、整改方案

### 2.1 充值退款：冻结余额模式（P0 #1）

**改造 `executeRefund()` 充值退款分支**：

```
当前：deductBalance → strategy.refund → 失败回退addBalance
改为：freezeBalance → strategy.refund → 成功: deductFrozenBalance / 失败: unfreezeBalance
```

**down机恢复**：
- 冻结成功后down机 → 余额已冻结，用户不可用但未丢失 → RefundStatusSyncScheduler 扫描 PROCESSING 退款单，查询第三方结果后执行扣减冻结或解冻
- 退款单还在 PENDING → 由新增的 PENDING 补偿扫描处理

### 2.2 支付回调：拆分事务外验签 + 事务内更新（P0 #2）

**改造 `handlePaymentCallback()`**：

```
当前：@Transactional { 验签(网络) → 解析 → 加锁 → 校验 → 更新 → Outbox }
改为：验签+解析(无事务) → @Transactional { 加锁 → 校验 → 更新 → Outbox }
```

**关键**：验签和解析移到事务外，只有数据库操作在事务内。

### 2.3 退款流程：拆分事务外调第三方 + 事务内更新（P0 #3）

**改造 `applyRefund()` 和 `executeRefund()`**：

```
当前：@Transactional { 加锁 → 校验 → 创建Refund → strategy.refund(网络) → 更新状态 → Outbox }
改为：
  Step 1 @Transactional: { 加锁 → 校验 → 创建Refund(PROCESSING) }
  Step 2 (无事务): strategy.refund(网络调用)
  Step 3 @Transactional: { 更新Refund状态 → 更新Order状态 → Outbox }
```

**down机恢复**：
- Step 1 完成后down机 → Refund 状态 PROCESSING → RefundStatusSyncScheduler 补偿
- Step 2 完成后down机 → 同上
- Step 3 由 RefundStatusSyncScheduler 扫描后补偿执行

### 2.4 退款回调签名验证失败返回失败（P0 #4）

```java
// 改为：
if (!paymentStrategy.verifyRefundCallback(callbackData)) {
    log.error("【安全告警】退款回调签名验证失败 - platform: {}", platform);
    return false;
}
```

Controller 收到 false 返回 FAILURE 给第三方，第三方会重试。重试是安全的，因为有幂等检查。

### 2.5 过期标记统一保守策略（P0 #5）

`PaymentOrderExpireScheduler.expirePendingOrders()` 中：
```java
// 当前：查询第三方失败时保守标记过期
// 改为：查询第三方失败时不标记过期，跳过该订单，等下一轮重试
if (checkThirdPartyFailed) {
    log.warn("查询第三方状态失败，跳过过期标记 - orderNo: {}", orderNo);
    continue; // 不加入 toExpire 列表
}
```

### 2.6 退款回调加行锁（P1 #6）

```java
// 当前：refundMapper.selectByRefundNo(refundNo)
// 改为：refundMapper.selectByRefundNoForUpdate(refundNo)
```

新增 `selectByRefundNoForUpdate` Mapper 方法，加 `FOR UPDATE` 行锁。

### 2.7 PENDING状态退款单补偿（P1 #7）

**改造 `RefundStatusSyncScheduler`**：

```java
// 当前：只扫描 PROCESSING 状态
// 改为：同时扫描 PENDING 和 PROCESSING 状态

// PENDING 状态的退款单：重新提交退款（调 executeRefund）
// PROCESSING 状态的退款单：查询第三方结果后更新
```

新增 `refundMapper.selectPendingForSync()` 方法。

### 2.8 余额变动MQ改用Outbox（P1 #8）

**改造 `WalletServiceImpl.executeBalanceOperation()`**：

```java
// 当前：TransactionSynchronizationUtils.afterCommit(() -> paymentEventProducer.sendBalanceChanged(mqMsg));
// 改为：paymentEventProducer.sendBalanceChangedToOutbox(mqMsg);
```

在 `PaymentEventProducer` 中新增 `sendBalanceChangedToOutbox()` 方法。

### 2.9 退款完成消息补充退款金额（P1 #9）

**改造 `updateBizOrderOnRefundSuccess()`**：

```java
RefundCompletedMessage msg = new RefundCompletedMessage();
msg.setRefundAmountFen(refund.getAmount()); // 补充退款金额
```

### 2.10 死信队列自动重试（P2 #10）

新增 `DlqRetryScheduler`：

```java
@Scheduled(fixedDelay = 300_000, initialDelay = 180_000)
public void retryDlqMessages() {
    // 1. 扫描各 DLQ 队列中的消息
    // 2. 重新投递到原业务队列
    // 3. 限制重试次数（避免无限重试）
}
```

使用 RabbitTemplate 从 DLQ 读取消息并重新投递。

### 2.11 去掉内存ScheduledExecutorService（P2 #11）

删除 `scheduleRefundStatusAutoSync()` 方法和 `paymentAsyncExecutor` 线程池。
完全依赖 `RefundStatusSyncScheduler` 的 Spring 定时扫描。

### 2.12 补偿同步加事务（P2 #12）

**改造 `syncPaymentOrderFromQuery()`**：

```java
// 当前：无事务
// 改为：@Transactional(rollbackFor = Exception.class)
```

确保"更新订单状态 + 入钱包/写Outbox"在同一事务内原子提交。

---

## 三、改动文件清单

| 文件 | 改动内容 |
|------|---------|
| `PaymentServiceImpl.java` | #1 退款冻结模式；#2 回调拆分；#3 退款拆分；#4 签名验证；#9 退款金额；#11 去内存线程池 |
| `RefundMapper.java` + XML | #6 新增 selectByRefundNoForUpdate；#7 新增 selectPendingForSync |
| `RefundStatusSyncScheduler.java` | #7 扫描 PENDING + PROCESSING |
| `PaymentOrderExpireScheduler.java` | #5 查询失败不标记过期 |
| `WalletServiceImpl.java` | #8 余额变动改 Outbox |
| `PaymentEventProducer.java` | #8 新增 sendBalanceChangedToOutbox |
| `OutboxMessageScheduler.java` | #8 支持 balance.changed 消息的签名重算 |
| `DlqRetryScheduler.java` (新建) | #10 死信队列自动重试 |
| `Refund.java` | 无改动（领域方法已完备） |
| `PaymentCallbackController.java` | 无改动（Controller 层不受影响） |

---

## 四、Down机场景覆盖矩阵

| 场景 | 当前行为 | 整改后行为 |
|------|---------|-----------|
| 支付成功，回调前down机 | 依赖用户查询触发补偿或5分钟Scheduler | 同上，但补偿同步加了事务，更安全 |
| 回调验签后、更新状态前down机 | 第三方已扣款但本地PENDING，等Scheduler | 不存在此窗口（验签在事务外） |
| 回调更新状态后、入钱包前down机 | 事务回滚，订单和钱包都不变（安全） | 同上 |
| 退款创建后、调第三方前down机 | Refund=PENDING，无补偿 | Refund=PROCESSING，Scheduler扫描补偿 |
| 第三方退款成功后、更新状态前down机 | Refund=PROCESSING，Scheduler扫描 | 同上，Scheduler扫描PENDING+PROCESSING |
| 充值退款：扣余额后、调第三方前down机 | 余额已扣，退款未发起，**资金不一致** | 余额已冻结（不可用但未扣除），Scheduler补偿后扣减或解冻 |
| 过期标记时第三方查询失败 | 可能误标EXPIRED | 跳过不标记，等下一轮 |
| 退款回调签名验证失败 | 返回SUCCESS，静默忽略 | 返回FAILURE，第三方重试 |
| MQ消息发送失败 | Outbox补偿（仅支付/退款消息） | 所有消息都走Outbox（含余额变动） |
| MQ消息消费失败进DLQ | 需人工处理 | DLQ自动重试 |

---

## 五、不做的事

1. 不引入 Spring Statemachine / Saga 等重框架
2. 不改变数据库表结构（无需新增 Flyway 迁移）
3. 不改变 MQ Exchange/Queue 拓扑
4. 不改变 drone-system 消费端代码（#9 补充退款金额后消费端自然兼容）
