# beenest-payment 支付中台接入文档

支付中台为 Beenest 旗下所有业务系统提供统一的支付、钱包、退款、提现能力。下游服务通过引入 `beenest-payment-api` 依赖，使用 Feign Client 调用内部 API。

> **适用版本**：`0.0.1-SNAPSHOT`（Java 21，Spring Boot 3.5.x）

---

## 目录

- [快速开始](#快速开始)
- [核心概念](#核心概念)
- [典型集成流程](#典型集成流程)
- [架构概览](#架构概览)
- [Feign 接口一览](#feign-接口一览)
- [支付分 — 信用免押](#支付分--信用免押)
- [支付回调机制](#支付回调机制)
- [内部 API 安全机制](#内部-api-安全机制)
- [MQ 消息消费指南](#mq-消息消费指南)
- [统一响应格式](#统一响应格式)
- [错误码参考](#错误码参考)
- [定时任务](#定时任务)
- [部署与运维](#部署与运维)
- [常见问题](#常见问题)

---

## 快速开始

### 1. 添加 Maven 依赖

```xml
<dependency>
    <groupId>club.beenest.payment</groupId>
    <artifactId>beenest-payment-api</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### 2. 启用 Feign Client

在启动类或配置类上添加注解：

```java
@EnableFeignClients(basePackages = "club.beenest.payment.feign")
```

### 3. 配置 application.yml

```yaml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:localhost:8848}
    openfeign:
      sentinel:
        enabled: true    # 启用 Fallback 降级

# 内部 API 安全配置（与支付中台共享相同密钥）
payment:
  internal:
    token: ${INTERNAL_TOKEN:}           # 静态令牌，必须与支付中台一致
    sign-secret: ${INTERNAL_SIGN_SECRET:}  # HMAC 签名密钥（可选，启用后增强安全性）
  mq:
    sign-secret: ${MQ_SIGN_SECRET:}    # MQ 消息验签密钥（如需消费消息）
```

### 4. 注入并使用

```java
@Autowired
private PaymentFeignClient paymentFeignClient;

// 查询用户钱包余额
Response<BigDecimal> balance = paymentFeignClient.getBalance("C20260101001", "DRONE_ORDER");
```

---

## 核心概念

### 用户编号（customerNo）

`customerNo` 是用户的唯一标识，来源于 CAS 统一认证系统。在 Feign 接口中，几乎所有方法的第一个参数都需要传入 `customerNo`。

| 要点 | 说明 |
|------|------|
| 来源 | CAS 认证通过后分配的用户编号 |
| 格式 | 以 `C` 开头的字符串，例如 `C202601261234567890123` |
| 与 userId 关系 | 在当前系统中 `customerNo` 即为 CAS 用户 ID |
| 获取方式 | 业务服务从 CAS 认证上下文中获取，通过 Feign 参数传递 |
| 钱包关联 | 每个用户按 `(customerNo, bizType)` 组合拥有独立钱包 |

> 支付中台不负责生成 `customerNo`，仅存储和使用。下游服务需确保传入正确的用户编号。

### 金额单位

**所有金额统一使用「分」为单位**，类型为 `Long`。例如 `10000` 表示 100 元。

| 场景 | 最小值 | 最大值 |
|------|--------|--------|
| 充值 | 100 分（1 元） | 10,000,000 分（10 万元） |
| 提现 | 10,000 分（100 元） | 5,000,000 分（5 万元） |

> DTO 中提供 `getAmountInYuan()` 方法可将分转换为元（`BigDecimal`，保留两位小数）。

### 业务类型（bizType）— 多租户钱包隔离

每个用户在不同业务线下拥有独立钱包，通过 `bizType` 参数区分。省略时默认使用 `DRONE_ORDER`。

| 常量 | 值 | 说明 |
|------|----|------|
| `BizTypeConstants.DRONE_ORDER` | `DRONE_ORDER` | 无人机订单（默认） |
| `BizTypeConstants.SHOP_ORDER` | `SHOP_ORDER` | 商城订单 |

### 支付平台与支付方式

**平台（platform）** — 支付渠道提供商：

| 值 | 说明 |
|----|------|
| `WECHAT` | 微信支付 |
| `ALIPAY` | 支付宝 |
| `DOUYIN` | 抖音支付 |

**支付方式（payType）** — 前端传入的支付方式标识：

| 值 | 对应平台 |
|----|----------|
| `wxpay` | 微信支付（默认 `WECHAT_APP`，可显式切到 `WECHAT_JSAPI`） |
| `alipay` | 支付宝支付（默认 `ALIPAY_APP`，可显式切到 `ALIPAY_JSAPI`） |
| `toutiao` | 抖音支付（默认 `DOUYIN_MINI`） |

**支付渠道方式（paymentMethod）** — 决定具体走哪条支付链路：

| 值 | 说明 |
|----|------|
| `WECHAT_APP` | 原生 App 微信支付，不依赖 openid |
| `WECHAT_JSAPI` | 微信内 / 小程序支付，依赖 openid |
| `ALIPAY_APP` | 原生 App 支付宝支付 |
| `ALIPAY_JSAPI` | 支付宝小程序 / JSAPI 支付 |
| `DOUYIN_APP` | 原生 App 抖音支付 |
| `DOUYIN_MINI` | 抖音小程序支付 |

**平台标识（platform）** — 前端运行环境：

| 值 | 说明 |
|----|------|
| `app` | 原生 App |
| `mp-weixin` | 微信小程序 |
| `mp-alipay` | 支付宝小程序 |
| `mp-toutiao` | 抖音小程序 |

**支付分平台（信用免押）** — 用于 `ServiceOrderCreateDTO.platform` 字段：

| 值 | 说明 |
|----|------|
| `WECHAT_PAYSCORE` | 微信支付分（信用免押） |
| `ALIPAY_ZHIMA` | 支付宝芝麻信用（先享后付/免押金） |

> 支付分平台与普通支付平台使用不同的常量，区分信用免押与即时支付两种业务模式。

---

## 典型集成流程

### 整体交互架构

```
┌─────────────┐   Feign (同步)    ┌──────────────────┐   SDK (同步)   ┌────────────────────┐
│ drone-system │ ── HTTP/RPC ──→  │ beenest-payment  │ ── API ────→  │ 微信 App / JSAPI   │
│ (业务服务)    │ ←─ Response ──   │   (支付中台)      │ ←─ 回调 ───   │ 支付宝 / 抖音      │
└──────┬──────┘                    └────────┬─────────┘               └────────────────────┘
       │                                    │
       │         RabbitMQ (异步)             │
       │ ←───────── 消息通知 ─────────────── │
       │        (支付完成/退款完成/等)         │
```

- **同步调用**：通过 `PaymentFeignClient`（OpenFeign + Nacos 服务发现 + Sentinel 熔断）
- **异步通知**：通过 RabbitMQ 发布事件消息，下游服务订阅消费
- **安全保障**：三层内部 API 安全（IP 白名单 → 静态 Token → HMAC 签名）+ MQ 消息 HMAC 验签

### 订单支付流程

```
  业务服务               支付中台                  第三方支付           前端
    │                     │                         │                │
    │  1. createOrderPayment(customerNo, dto)       │                │
    │ ──────────────────→ │                         │                │
    │                     │  2. 创建支付订单         │                │
    │                     │  3. 按 paymentMethod 选择  │                │
    │                     │     App / JSAPI / 小程序  │                │
    │                     │  4. 调用第三方预支付 API   │                │
    │                     │ ──────────────────────→ │                │
    │                     │ ←─ prepay_id/参数 ────── │                │
    │  ← {orderNo, paymentParams} │                 │                │
    │ ──────────────────────────────────────────────────────────→   │
    │                     │                         │  5. 透传参数    │
    │                     │                         │ ←── 拉起支付 ── │
    │                     │  6. 用户完成支付         │                │
    │                     │ ←──── 异步回调 ───────── │                │
    │                     │  7. 验签 + 更新订单状态   │                │
    │                     │  8. 发布 MQ 消息         │                │
    │ ←── payment.order.completed ── │               │                │
    │  9. 更新业务订单状态  │                         │                │
```

**调用示例**：

```java
// 1. 构建支付请求
OrderPaymentRequestDTO request = new OrderPaymentRequestDTO();
request.setBizNo("PLAN20260421001");
request.setPayType("wxpay");
request.setPlatform("app");
request.setPaymentMethod("WECHAT_APP");

// 2. 发起支付
Response<Map> result = paymentFeignClient.createOrderPayment("C20260126001", request);
Map paymentParams = (Map) result.getData().get("paymentParams");

// 3. 将 paymentParams 返回给 App，App 调起微信支付
// 4. 监听 MQ 消息 payment.order.completed 确认支付结果
```

如果是微信小程序场景，则将 `paymentMethod` 设置为 `WECHAT_JSAPI`，并确保用户已完成微信授权，支付中台会从登录态或 CAS 统一用户资料中回查 `openid`。

### 充值流程

```java
// 1. 创建充值订单
RechargeRequestDTO request = new RechargeRequestDTO();
request.setAmount(10000L);  // 100 元
request.setPlatform("WECHAT");
request.setPaymentMethod("WECHAT_APP");

Response<Map> result = paymentFeignClient.createRechargeOrder("C20260126001", request);

// 2. 透传 paymentParams 给 App
// 3. 第三方回调后自动充值到钱包
// 4. 监听 MQ 消息 payment.balance.changed 确认到账
```

> `WECHAT_APP` 不需要 openid。只有 `WECHAT_JSAPI` 才会依赖 openid，且 openid 由支付中台服务端回查，不由前端直接传入。

用户前端应调用 `POST /api/customer/payment/recharge`；如果是业务系统代发起充值，则通过 Feign/内部接口走 `/internal/payment/payment/recharge`。

### 退款流程

```
  业务服务               支付中台                  第三方支付
    │                     │                         │
    │  1. applyRefund(orderNo, amount, reason)      │
    │ ──────────────────→ │                         │
    │                     │  2. 创建退款单           │
    │                     │  3. 调用第三方退款 API    │
    │                     │ ──────────────────────→ │
    │                     │ ←──── 退款结果 ───────── │
    │                     │  4. 更新退款状态         │
    │                     │  5. 余额回滚 + 发布 MQ   │
    │ ←─ payment.refund.completed ── │               │
    │  6. 更新业务订单状态  │                         │
```

> **审核退款**：如果是 `requestReview` + `audit` 模式，流程为：申请审核 → 管理员审核通过 → 系统自动发起退款 → MQ 通知。

---

## 架构概览

### DDD 聚合划分

支付中台采用 DDD（领域驱动设计）限界上下文划分，以聚合为代码组织单元，每个聚合拥有独立的实体、枚举、DTO、Mapper、Service 和策略。

```
club.beenest.payment
├── paymentorder/          聚合1: 支付订单 + 退款
│   ├── controller/        ← PaymentCustomerController (C端充值/支付), PaymentCallbackController (渠道回调)
│   ├── domain/entity/     ← PaymentOrder, Refund, PaymentEvent (充血模型)
│   ├── domain/enums/      ← PaymentOrderStatus, RefundStatus, OrderRefundPolicy...
│   ├── mapper/            ← PaymentOrderMapper, RefundMapper, PaymentEventMapper
│   ├── service/           ← IPaymentService, IPaymentEventService
│   ├── strategy/          ← PaymentStrategy (模板方法 + 工厂 + 微信/支付宝/抖音实现)
│   ├── config/            ← PaymentConfig, RedisKeyExpiredConfig
│   ├── scheduler/         ← PaymentOrderExpireScheduler, RefundStatusSyncScheduler
│   ├── listener/          ← PaymentOrderExpireListener
│   └── mq/producer/       ← PaymentEventProducer
│
├── wallet/                聚合2: 钱包
│   ├── controller/        ← WalletCustomerController (C端余额/交易查询)
│   ├── domain/entity/     ← Wallet, WalletTransaction, WalletIntegrityLog (充血模型)
│   ├── domain/enums/      ← WalletStatus, WalletTransactionType...
│   ├── mapper/            ← WalletMapper, WalletTransactionMapper, WalletIntegrityLogMapper
│   ├── service/           ← IWalletService, IWalletIntegrityService
│   ├── security/          ← BalanceHashCalculator (HMAC 余额哈希)
│   ├── scheduler/         ← WalletIntegrityScheduler
│   └── mq/consumer/       ← WalletCreditConsumer (消费入账指令)
│
├── withdraw/              聚合3: 提现
│   ├── controller/        ← WithdrawCustomerController (C端提现)
│   ├── domain/entity/     ← WithdrawRequest (充血模型)
│   ├── service/           ← IWithdrawService + WithdrawLimitChecker + WithdrawRiskChecker
│   └── strategy/          ← WithdrawStrategy (模板方法 + 工厂)
│
├── payscore/              聚合5: 支付分（信用免押）
│   ├── controller/        ← PayScoreCustomerController (C端授权/查询), PayScoreCallbackController (授权/完结回调)
│   ├── domain/entity/     ← ServiceOrder, CreditAuthorization (充血模型)
│   ├── domain/enums/      ← ServiceOrderStatus, ServiceOrderType
│   ├── dto/               ← ServiceOrderCreateDTO, ServiceOrderResultDTO, CreditCheckResultDTO, ServiceOrderCompleteDTO, ServiceOrderCancelDTO
│   ├── mapper/            ← ServiceOrderMapper, CreditAuthorizationMapper
│   ├── service/           ← IServiceOrderService, ServiceOrderServiceImpl
│   └── strategy/          ← PayScoreStrategy (模板方法 + 工厂 + 微信支付分/支付宝芝麻信用实现)
│
├── reconciliation/        聚合4: 对账
│   ├── domain/entity/     ← ReconciliationTask
│   ├── service/           ← IReconciliationService + 4 种策略实现
│   └── config/            ← ReconciliationStrategyFactory
│
├── shared/                共享内核
│   ├── domain/entity/     ← RiskRule, PaymentChannelConfig, ExchangeCode, OutboxMessage
│   ├── service/           ← IRiskRuleService, IPaymentConfigService
│   └── scheduler/         ← OutboxMessageScheduler (Outbox 模式 MQ 重试)
│
└── admin/                 管理端门面
    ├── controller/
    │   ├── PaymentAdminController      ← 订单+退款+配置管理
    │   ├── WalletAdminController       ← 钱包查询+交易导出
    │   ├── WithdrawAdminController     ← 提现审核+导出
    │   ├── ReconciliationAdminController ← 对账管理
    │   └── internal/
    │       └── InternalPaymentController ← Feign 契约实现门面
    └── (按聚合拆分，避免 God Controller)
```

### 跨聚合调用规则

- 聚合之间**禁止直接注入其他聚合的 Mapper**，必须通过 Service 接口调用
- 例：`WithdrawServiceImpl` 需要操作钱包余额时，调用 `IWalletService.freezeBalance()` 而非 `WalletMapper`
- 事务边界保持在 Service 层，跨聚合调用使用 `REQUIRED` 传播行为

### 充血模型

聚合根实体封装状态转换逻辑，外部通过领域方法操作而非 `setStatus()` + `setXxx()`：

| 聚合根 | 领域方法 | 说明 |
|--------|---------|------|
| `PaymentOrder` | `markAsPaid(callbackData, transactionNo)` | 支付成功（含状态守卫） |
| | `cancel()` | 取消订单 |
| | `expire()` | 过期标记 |
| | `markAsRefunded()` / `markAsPartialRefunded()` | 退款状态 |
| `Refund` | `markAsSuccess(thirdPartyRefundNo, channelStatus)` | 退款成功 |
| | `markAsFailed(reason)` | 退款失败 |
| | `markAsProcessing(thirdPartyRefundNo)` | 退款处理中 |
| `Wallet` | `hasEnoughFrozenBalance(amount)` | 校验冻结余额 |
| | `canFreeze()` / `canUnfreeze()` | 钱包状态校验 |
| `ServiceOrder` | `markAsAuthorized(frozenAmount, thirdPartyOrderNo)` | 授权成功（含状态守卫） |
| | `markAsServiceActive()` | 进入服务中 |
| | `markAsCompleting(actualAmount)` | 发起完结扣款 |
| | `markAsCompleted(callbackData)` | 完结确认 |
| | `markAsCancelled()` | 取消授权解冻 |
| | `markAsExpired()` / `markAsFailed()` | 过期/失败 |
| `CreditAuthorization` | `isFullExempt()` / `isPartialExempt()` / `isNotExempt()` | 免押结果判断 |
| | `markAsAuthorized(creditScore, frozenAmount, thirdPartyAuthNo)` | 授权确认 |

---

## Feign 接口一览

Feign Client 基路径：`/internal/payment`，所有端点都在此路径下。

### 钱包操作

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/wallet/balance/{customerNo}` | 查询可用余额（返回 `BigDecimal`，单位分） |
| `GET` | `/wallet/detail/{customerNo}` | 查询钱包详情（可用余额、冻结余额、累计充值/提现/消费等） |
| `GET` | `/wallet/{customerNo}` | 查询钱包完整实体 |
| `POST` | `/wallet/create/{customerNo}` | 创建钱包（如已存在则返回已有钱包） |
| `GET` | `/wallet/transactions/{customerNo}` | 分页查询交易记录 |
| `POST` | `/wallet/add-balance` | 增加余额（系统充值、退款回滚等场景） |
| `POST` | `/wallet/deduct-balance` | 扣减余额（返回 `Boolean`，余额不足时返回 `false`） |
| `POST` | `/wallet/freeze-balance` | 冻结余额（可用→冻结，提现/担保场景） |
| `POST` | `/wallet/unfreeze-balance` | 解冻余额（冻结→可用，取消提现/释放担保） |

**公共参数**：以上接口均支持可选参数 `bizType`（业务类型），省略时默认 `DRONE_ORDER`。

**分页接口返回结构**（`Map<String, Object>`）：

```json
{
  "total": 100,
  "list": [...],
  "pageNum": 1,
  "pageSize": 20
}
```

**add-balance / deduct-balance 参数说明**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `customerNo` | String | 是 | 用户编号 |
| `bizType` | String | 否 | 业务类型 |
| `amount` | BigDecimal | 是 | 变动金额（单位：分） |
| `description` | String | 是 | 交易描述 |
| `transactionType` | String | 是 | 交易类型（`RECHARGE`/`PAYMENT`/`REFUND`/`WITHDRAW`/`FEE`/`PENALTY`） |
| `referenceNo` | String | 否 | 关联单号 |

**freeze-balance / unfreeze-balance 参数说明**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `customerNo` | String | 是 | 用户编号 |
| `bizType` | String | 否 | 业务类型 |
| `amount` | Long | 是 | 冻结/解冻金额（单位：分） |
| `description` | String | 是 | 操作描述 |
| `referenceNo` | String | 否 | 关联单号（如提现申请号） |

> **冻结/解冻流程**：提现时先冻结余额 → 审核通过后扣减冻结余额 → 如审核拒绝则解冻归还。下游服务通常无需直接调用冻结接口，提现流程由支付中台内部自动管理。

### 充值 / 支付

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/payment/recharge` | 创建充值订单（钱包充值） |
| `POST` | `/payment/order-payment` | 创建订单支付（业务订单付款） |
| `GET` | `/payment/status/{orderNo}` | 查询支付状态（需传 customerNo） |
| `GET` | `/payment/status-admin/{orderNo}` | 管理端查询支付状态（无需 customerNo） |
| `POST` | `/payment/cancel/{orderNo}` | 取消充值订单 |
| `POST` | `/payment/orders` | 分页查询支付订单 |

**RechargeRequestDTO**（充值请求体）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `amount` | Long | 是 | 充值金额（分），最小 100 |
| `platform` | String | 是 | 支付平台：`WECHAT`/`ALIPAY`/`DOUYIN` |
| `paymentMethod` | String | 否 | 支付方式，不传时根据 platform 自动选择 |
| `returnUrl` | String | 否 | 支付完成跳转地址 |
| `remark` | String | 否 | 备注 |

**OrderPaymentRequestDTO**（订单支付请求体）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `bizNo` | String | 是 | 业务单号（由调用方传入，如订单计划编号） |
| `amount` | Long | 否 | 支付金额（分），不传时由服务端根据 bizNo 计算 |
| `payType` | String | 是 | 支付方式：`wxpay`/`alipay`/`toutiao` |
| `platform` | String | 是 | 平台标识：`mp-weixin`/`mp-alipay`/`mp-toutiao` |
| `couponId` | Long | 否 | 优惠券 ID |
| `bizType` | String | 否 | 业务类型 |
| `originalAmount` | Long | 否 | 原始金额（分，前端展示用） |
| `discountAmount` | Long | 否 | 优惠金额（分） |

> 微信支付的 `openid` 不再由请求体传入，而是优先从当前 CAS 登录态读取；如果登录态未携带，则按 `customerNo` 回查 CAS 统一用户资料，并写入支付订单扩展字段 `ext.openid`。

**createRechargeOrder / createOrderPayment 返回值**（`Map<String, Object>`）：

```json
{
  "orderNo": "PAY202603041234567890",
  "paymentParams": { ... }  // 前端调起支付所需参数（因平台而异）
}
```

### 退款

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/refund/apply` | 直接申请退款（管理员操作） |
| `POST` | `/refund/request-review` | 申请退款审核（需后续 audit 审批） |
| `POST` | `/refund/list` | 分页查询退款记录 |
| `GET` | `/refund/sync/{refundNo}` | 同步单个退款状态（从第三方渠道拉取最新状态） |
| `POST` | `/refund/sync-processing` | 批量同步处理中的退款（传 `limit` 控制批量大小） |
| `POST` | `/refund/audit` | 审核退款（通过/拒绝） |

**apply / request-review 参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `orderNo` | String | 是 | 原支付订单号 |
| `amount` | Long | 是 | 退款金额（分） |
| `reason` | String | 是 | 退款原因 |

**audit 参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | Long | 是 | 退款记录 ID |
| `status` | String | 是 | 审核结果：`APPROVED`/`REJECTED` |
| `remark` | String | 是 | 审核备注 |

### 提现

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/withdraw/create` | 创建提现申请 |
| `GET` | `/withdraw/status/{requestNo}` | 查询提现状态 |
| `POST` | `/withdraw/audit` | 审核提现申请 |
| `POST` | `/withdraw/process/{requestNo}` | 执行提现（审核通过后调用） |
| `POST` | `/withdraw/cancel` | 取消提现申请 |
| `POST` | `/withdraw/list` | 分页查询提现记录 |

**WithdrawRequestDTO**（提现请求体）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `amount` | Long | 是 | 提现金额（分），最小 10,000（100 元） |
| `withdrawType` | String | 是 | 提现类型：`ALIPAY`/`BANK_CARD` |
| `accountType` | String | 是 | 账户类型：`PERSONAL`/`COMPANY` |
| `accountName` | String | 是 | 账户姓名 |
| `accountNumber` | String | 是 | 支付宝账号或银行卡号 |
| `bankName` | String | 条件必填 | 银行名称（银行卡提现时必填） |
| `bankBranch` | String | 否 | 开户行支行 |
| `remark` | String | 否 | 备注 |

**提现手续费规则**：支付宝 2 元，银行卡 5 元。实际到账 = 提现金额 - 手续费。

### 内部查询（供下游服务查询支付数据）

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/payment/latest-by-biz-no/{bizNo}` | 根据业务单号查询最新支付订单 |
| `GET` | `/refund/list-by-order/{orderNo}` | 查询订单关联的所有退款记录 |
| `GET` | `/refund/latest-pending-by-order/{orderNo}` | 查询订单最新的待处理退款 |
| `GET` | `/wallet/transactions/raw/{customerNo}` | 分页查询用户原始交易记录 |
| `GET` | `/wallet/transactions/statistics/{customerNo}` | 查询用户收支统计（按时间段，支持 `startTime`/`endTime` 参数） |

### 支付分 — 信用免押（Feign）

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/payscore/check-credit` | 信用免押检查（查询用户是否满足免押条件） |
| `POST` | `/payscore/create` | 创建服务订单（发起信用免押授权） |
| `POST` | `/payscore/complete/{orderNo}` | 完结服务订单（扣取实际费用，解冻剩余额度） |
| `POST` | `/payscore/cancel/{orderNo}` | 取消服务订单（取消授权，解冻额度） |
| `GET` | `/payscore/status/{orderNo}` | 查询服务订单状态 |
| `POST` | `/payscore/modify/{orderNo}` | 修改服务订单冻结金额 |
| `GET` | `/payscore/latest-by-biz-no/{bizNo}` | 根据业务单号查询最新服务订单 |

**check-credit 参数说明**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `customerNo` | String | 是 | 用户/商户编号 |
| `platform` | String | 是 | 支付分平台：`WECHAT_PAYSCORE` / `ALIPAY_ZHIMA` |
| `depositAmount` | Long | 是 | 保证金金额（分） |

**CreditCheckResultDTO 返回结构**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `eligible` | boolean | 是否满足免押条件 |
| `exemptionResult` | String | 免押结果：`FULL_EXEMPT`（完全免押）/ `PARTIAL_EXEMPT`（部分免押）/ `NOT_EXEMPT`（不满足免押） |
| `creditScore` | Integer | 用户信用分（微信支付分场景为 null） |
| `depositAmount` | Long | 原始保证金金额（分） |
| `frozenAmount` | Long | 实际需冻结金额（分），完全免押时为 0 |
| `message` | String | 免押说明 |

**ServiceOrderCreateDTO（创建服务订单请求体）**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `bizNo` | String | 是 | 关联业务单号（如入驻申请编号） |
| `bizType` | String | 否 | 业务类型，默认 `MERCHANT_DEPOSIT` |
| `platform` | String | 是 | 支付分平台：`WECHAT_PAYSCORE` / `ALIPAY_ZHIMA` |
| `depositAmount` | Long | 是 | 保证金金额（分），必须 > 0 |
| `channelUserId` | String | 否 | 渠道用户标识（微信 openid / 支付宝 userId） |
| `remark` | String | 否 | 备注信息 |

**ServiceOrderResultDTO 返回结构**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `orderNo` | String | 服务订单号 |
| `bizNo` | String | 关联业务单号 |
| `platform` | String | 支付分平台标识 |
| `platformName` | String | 平台显示名称 |
| `status` | String | 服务订单状态（见状态流转图） |
| `statusDisplayName` | String | 状态中文显示名称 |
| `depositAmount` | Long | 原始保证金金额（分） |
| `frozenAmount` | Long | 实际冻结金额（分） |
| `actualAmount` | Long | 完结实际扣款金额（分，0 表示全额解冻） |
| `authParams` | Map | 授权跳转参数（创建服务订单时返回，前端用于跳转授权页面） |
| `expireTime` | LocalDateTime | 过期时间 |
| `authTime` | LocalDateTime | 授权时间 |
| `completeTime` | LocalDateTime | 完结时间 |

**complete 参数说明**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `orderNo` | String (路径) | 是 | 服务订单号 |
| `actualAmount` | Long | 是 | 实际扣款金额（分），0 表示全额解冻，不能超过冻结金额 |

**modify 参数说明**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `orderNo` | String (路径) | 是 | 服务订单号 |
| `newAmount` | Long | 是 | 新冻结金额（分），必须 > 0 |

> ⚠️ **注意**：支付宝芝麻信用不支持直接修改冻结金额，调用 modify 会抛出 `BusinessException`。需取消当前订单后重新创建。

### 管理端

管理端 API 按聚合拆分为 4 个独立控制器，统一路径前缀 `/api/admin/payment`，需要 `ADMIN` 角色。

**PaymentAdminController**（订单+退款+配置）：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/orders/page` | 分页查询充值订单 |
| `POST` | `/orders/{orderNo}/sync` | 同步第三方订单状态 |
| `POST` | `/orders/export` | 导出订单 CSV |
| `POST` | `/refunds/apply` | 申请退款 |
| `POST` | `/refunds/page` | 分页查询退款记录 |
| `POST` | `/refunds/audit` | 审核退款 |
| `POST` | `/refunds/{refundNo}/sync` | 同步第三方退款状态 |
| `POST` | `/refunds/sync-processing` | 批量同步处理中退款 |
| `POST` | `/refunds/export` | 导出退款 CSV |
| `GET` | `/configs/list` | 获取所有支付渠道配置 |
| `POST` | `/configs/update` | 更新支付渠道配置 |

**WalletAdminController**（钱包+交易）：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/wallets/page` | 分页查询钱包列表 |
| `POST` | `/transactions/page` | 分页查询交易流水 |
| `POST` | `/transactions/export` | 导出交易流水 CSV |

**WithdrawAdminController**（提现）：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/withdraws/page` | 分页查询提现申请 |
| `POST` | `/withdraws/audit` | 审核提现申请（通过/拒绝） |
| `POST` | `/withdraws/export` | 导出提现 CSV |

**ReconciliationAdminController**（对账）：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/reconciliation/tasks` | 分页查询对账任务 |
| `POST` | `/reconciliation/create` | 创建对账任务（传 `date` 和 `channel`） |
| `GET` | `/reconciliation/event-logs` | 查询对账事件日志 |

**内部管理端**（Feign 契约门面，`/internal/payment/admin/`）：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/admin/wallets` | 分页查询钱包列表 |
| `POST` | `/admin/transactions` | 分页查询交易流水 |
| `POST` | `/admin/reconciliation/tasks` | 分页查询对账任务 |
| `POST` | `/admin/reconciliation/create` | 创建对账任务 |

---

## 支付分 — 信用免押

支付分（PayScore）是支付中台的信用免押聚合，支持**微信支付分**和**支付宝芝麻信用**两种平台，用于商户入驻保证金免押、先享后付等信用场景。

### 核心概念

**信用免押 vs 普通支付**：

| 维度 | 普通支付 | 信用免押（支付分） |
|------|---------|-------------------|
| 交易模式 | 下单 → 支付 → 回调确认（即时交易） | 创建授权 → 用户确认 → 冻结额度 → 服务中 → 完结扣款 → 解冻 |
| 资金流向 | 即时扣款 | 先冻结信用额度，服务结束后按实际费用扣款 |
| 适用场景 | 商品购买、充值 | 商户入驻免押金、先享后付、押金减免 |
| 退款方式 | 主动申请退款 | 完结时 actualAmount=0 全额解冻，或 actualAmount<冻结金额 部分扣款+部分解冻 |

**免押结果**：

| 值 | 说明 |
|----|------|
| `FULL_EXEMPT` | 完全免押 — 信用分达标，无需缴纳保证金，冻结金额为 0 |
| `PARTIAL_EXEMPT` | 部分免押 — 信用分较高，仅需缴纳部分保证金 |
| `NOT_EXEMPT` | 不满足免押 — 信用分不足，需全额缴纳保证金 |

> 微信支付分不做前端信用评估，实际免押判断在授权流程中由微信侧完成。支付宝芝麻信用通过 `ZhimaCreditScoreGetRequest` 查询芝麻分，根据配置的 `minCreditScore` 阈值判断。

### 服务订单状态流转

```
  PENDING_AUTH ──→ AUTHORIZED ──→ SERVICE_ACTIVE ──→ COMPLETING ──→ COMPLETED
       │                │                │                │
       │                │                │                └──→ FAILED
       │                │                │
       │                └──→ CANCELLED ←─┘
       │
       ├──→ EXPIRED
       ├──→ CANCELLED
       └──→ FAILED
```

| 状态 | 说明 | 是否终态 |
|------|------|---------|
| `PENDING_AUTH` | 待授权 — 等待用户跳转授权页面确认 | 否 |
| `AUTHORIZED` | 已授权 — 用户已确认，信用额度已冻结 | 否 |
| `SERVICE_ACTIVE` | 服务中 — 商户已入驻，服务进行中 | 否 |
| `COMPLETING` | 完结中 — 已发起完结扣款请求，等待确认 | 否 |
| `COMPLETED` | 已完结 — 扣款完成，剩余额度已解冻 | 是 |
| `CANCELLED` | 已取消 — 授权已取消，额度已解冻 | 是 |
| `EXPIRED` | 已过期 — 授权超时未确认 | 是 |
| `FAILED` | 失败 — 授权失败或完结失败 | 是 |

### 信用免押流程

```
  业务服务                 支付中台                  第三方支付分           前端
    │                       │                         │                │
    │  1. checkCreditEligibility(customerNo, platform, depositAmount)
    │ ────────────────────→ │                         │                │
    │ ←─ CreditCheckResult  │                         │                │
    │                       │                         │                │
    │  2. createServiceOrder(customerNo, request)      │                │
    │ ────────────────────→ │  3. 调用第三方创建授权   │                │
    │                       │ ──────────────────────→ │                │
    │                       │ ←─ 授权跳转参数 ──────── │                │
    │ ←─ {orderNo, authParams} │                      │                │
    │ ──────────────────────────────────────────────────────────────→  │
    │                       │                         │  4. 跳转授权页面 │
    │                       │                         │ ←── 用户确认 ── │
    │                       │  5. 授权回调通知         │                │
    │                       │ ←──── 异步回调 ───────── │                │
    │                       │  6. 验签 + 更新状态      │                │
    │                       │     AUTHORIZED           │                │
    │                       │                         │                │
    │  7. completeServiceOrder(orderNo, actualAmount)  │                │
    │ ────────────────────→ │  8. 调用第三方完结扣款   │                │
    │                       │ ──────────────────────→ │                │
    │                       │ ←──── 完结确认 ───────── │                │
    │ ←─ ServiceOrderResult │  9. 更新状态 COMPLETED   │                │
```

**调用示例**：

```java
// 1. 信用免押检查（可选，微信支付分场景可跳过）
CreditCheckResultDTO checkResult = paymentFeignClient
    .checkCreditEligibility("C20260126001", "WECHAT_PAYSCORE", 100000L)
    .getData();
if (!checkResult.isEligible()) {
    // 用户不满足免押条件，走普通支付缴纳保证金
}

// 2. 创建服务订单（发起授权）
ServiceOrderCreateDTO request = new ServiceOrderCreateDTO();
request.setBizNo("MCH20260615001");       // 入驻申请编号
request.setPlatform("WECHAT_PAYSCORE");    // 微信支付分
request.setDepositAmount(100000L);          // 保证金 1000 元（分）
request.setChannelUserId("o2xYH5a8HnR1ExampleOpenid"); // 微信 openid（JSAPI 场景）

ServiceOrderResultDTO result = paymentFeignClient
    .createServiceOrder("C20260126001", request)
    .getData();
String orderNo = result.getOrderNo();
Map<String, Object> authParams = result.getAuthParams();
// 3. 将 authParams 返回给前端，跳转微信支付分授权页面

// 4. 服务期结束后完结（actualAmount=0 表示无扣款，全额解冻）
ServiceOrderResultDTO completeResult = paymentFeignClient
    .completeServiceOrder(orderNo, 0L)
    .getData();

// 5. 如需取消（取消授权，全额解冻）
paymentFeignClient.cancelServiceOrder(orderNo);
```

### 支付分平台差异

| 维度 | 微信支付分 | 支付宝芝麻信用 |
|------|-----------|--------------|
| 平台标识 | `WECHAT_PAYSCORE` | `ALIPAY_ZHIMA` |
| 信用评估 | 不做前端评估，授权时由微信侧判定 | 查询芝麻分，与 `minCreditScore` 阈值比较 |
| 创建订单 API | POST `/v3/payscore/serviceorder` | `ZhimaCreditEpFreedepositInitialize` |
| 完结订单 API | POST `/v3/payscore/serviceorder/{no}/complete` | `ZhimaCreditPayafteruseCreditbizorderFinish` |
| 取消订单 API | POST `/v3/payscore/serviceorder/{no}/cancel` | `ZhimaCreditEpFreedepositApply`（传入 0 元解冻） |
| 修改金额 | 支持（POST `/v3/payscore/serviceorder/{no}/modify`） | **不支持**（需取消后重新创建） |
| 回调签名验证 | wechatpay-java SDK `NotificationParser` | `AlipaySignature.rsaCheckV1` |
| 回调成功响应 | `{"code":"SUCCESS","message":"成功"}` | `success` |
| 免押模式 | 信用免押（DEPOSIT） | 免押金（Freedeposit）+ 先享后付（Payafteruse） |

### 回调端点

| 端点 | 说明 |
|------|------|
| `POST /api/payscore/callback/auth/{platform}` | 授权确认回调 |
| `POST /api/payscore/callback/complete/{platform}` | 完结确认回调 |

`{platform}` 为支付分平台标识（`WECHAT_PAYSCORE` / `ALIPAY_ZHIMA`），由 `PayScoreStrategyFactory` 自动路由到对应策略。

> 回调路径已加入 CAS `ignore-pattern`，不触发认证拦截：`/api/payscore/callback/**`

### 客户端 API

用户前端直接调用的接口（需 CAS 认证）：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/customer/payscore/create` | 创建服务订单（发起授权） |
| `GET` | `/api/customer/payscore/status/{orderNo}` | 查询服务订单状态 |
| `POST` | `/api/customer/payscore/cancel/{orderNo}` | 取消服务订单（解冻额度） |

### 配置

**application.yml 配置项**：

```yaml
payment:
  payscore:
    wechat:
      enabled: ${PAYSCORE_WECHAT_ENABLED:false}          # 是否启用微信支付分
      service-id: ${PAYSCORE_WECHAT_SERVICE_ID:}          # 微信支付分服务ID（由微信支付运营分配）
      app-id: ${WECHAT_APP_ID:}                           # 小程序AppID（复用微信支付配置）
      mch-id: ${WECHAT_MCH_ID:}                           # 商户号（复用微信支付配置）
      notify-url: ${PAYSCORE_WECHAT_NOTIFY_URL:}          # 授权回调地址
      complete-notify-url: ${PAYSCORE_WECHAT_COMPLETE_NOTIFY_URL:}  # 完结回调地址（可选，默认复用授权回调地址）
    alipay:
      enabled: ${PAYSCORE_ALIPAY_ENABLED:false}           # 是否启用支付宝芝麻信用免押
      product-code: ${PAYSCORE_ALIPAY_PRODUCT_CODE:}      # 芝麻信用产品码（由支付宝分配）
      app-id: ${ALIPAY_APP_ID:}                           # 小程序AppID（复用支付宝支付配置）
      notify-url: ${PAYSCORE_ALIPAY_NOTIFY_URL:}          # 授权回调地址
    common:
      auth-expire-minutes: 30                             # 授权超时时间（分钟）
      deposit-default-amount: 100000                      # 默认保证金金额（分，即 1000 元）
      min-credit-score: 600                               # 信用免押最低信用分阈值（芝麻信用场景）
```

**关键环境变量**：

| 变量名 | 必填 | 说明 |
|--------|------|------|
| `PAYSCORE_WECHAT_ENABLED` | 否 | 启用微信支付分（默认 `false`） |
| `PAYSCORE_WECHAT_SERVICE_ID` | 条件必填 | 微信支付分服务ID（启用时必填） |
| `PAYSCORE_WECHAT_NOTIFY_URL` | 条件必填 | 微信支付分授权回调地址 |
| `PAYSCORE_WECHAT_COMPLETE_NOTIFY_URL` | 否 | 微信支付分完结回调地址（不填则复用授权回调地址） |
| `PAYSCORE_ALIPAY_ENABLED` | 否 | 启用支付宝芝麻信用免押（默认 `false`） |
| `PAYSCORE_ALIPAY_PRODUCT_CODE` | 条件必填 | 芝麻信用产品码（启用时必填） |
| `PAYSCORE_ALIPAY_NOTIFY_URL` | 条件必填 | 支付宝芝麻信用回调地址 |

### 数据库

Flyway 迁移：`V1_0_8__payscore_credit_exemption.sql`

**ds_service_order** — 服务订单表：

| 字段 | 类型 | 说明 |
|------|------|------|
| `order_no` | VARCHAR(64) | 服务订单号（唯一） |
| `biz_no` | VARCHAR(64) | 关联业务单号（入驻申请编号） |
| `biz_type` | VARCHAR(32) | 业务类型（默认 `MERCHANT_DEPOSIT`） |
| `customer_no` | VARCHAR(64) | 用户/商户编号 |
| `platform` | VARCHAR(32) | 支付分平台 |
| `service_id` | VARCHAR(64) | 支付分服务ID |
| `deposit_amount` | BIGINT | 原始保证金金额（分） |
| `frozen_amount` | BIGINT | 实际冻结金额（分） |
| `actual_amount` | BIGINT | 完结实际扣款金额（分，0=全额解冻） |
| `status` | VARCHAR(32) | 服务订单状态 |
| `auth_time` | TIMESTAMP | 授权时间 |
| `complete_time` | TIMESTAMP | 完结时间 |
| `expire_time` | TIMESTAMP | 过期时间 |
| `third_party_order_no` | VARCHAR(128) | 第三方服务订单号 |
| `callback_data` | TEXT | 回调数据（JSON） |
| `ext` | TEXT | 扩展字段（JSON，含 channelUserId 等） |

**ds_credit_authorization** — 信用授权记录表：

| 字段 | 类型 | 说明 |
|------|------|------|
| `auth_no` | VARCHAR(64) | 授权编号（唯一） |
| `order_no` | VARCHAR(64) | 关联服务订单号 |
| `customer_no` | VARCHAR(64) | 用户/商户编号 |
| `platform` | VARCHAR(32) | 支付分平台 |
| `credit_score` | INTEGER | 用户信用分 |
| `deposit_amount` | BIGINT | 保证金金额（分） |
| `exemption_result` | VARCHAR(32) | 免押结果（`FULL_EXEMPT`/`PARTIAL_EXEMPT`/`NOT_EXEMPT`） |
| `frozen_amount` | BIGINT | 实际冻结金额（分） |
| `auth_status` | VARCHAR(32) | 授权状态 |

### 设计模式

支付分聚合与支付订单聚合平行，采用相同的设计模式：

- **策略模式** — `PayScoreStrategy` 接口定义信用免押通用操作，`WechatPayScoreStrategy` / `AlipayZhimaStrategy` 为具体实现
- **模板方法模式** — `AbstractPayScoreStrategy` 实现流程骨架（参数校验 → 调用子类 → 异常处理），子类实现 `doXxx()` 扩展点
- **工厂模式** — `PayScoreStrategyFactory` 自动注册所有 `PayScoreStrategy` Bean，按 `platform` 路由到对应策略
- **充血模型** — `ServiceOrder` 封装状态转换逻辑（`markAsAuthorized` / `markAsCompleted` 等），外部通过领域方法操作

---

## 支付回调机制

第三方支付平台（微信/支付宝/抖音）在用户完成支付后，会异步回调支付中台通知支付结果。

### 回调端点

| 端点 | 说明 |
|------|------|
| `POST /api/wallet/payment/callback/{platform}` | 支付结果回调 |
| `POST /api/wallet/payment/refund/callback/{platform}` | 退款结果回调 |

`{platform}` 为支付平台标识（`wechat`/`alipay`/`douyin`），由 `PaymentStrategyFactory` 自动路由到对应的策略实现。

### 回调安全（IP 白名单）

回调端点受 `CallbackIpWhitelistInterceptor` 保护，仅允许配置的 IP 访问：

```yaml
payment:
  callback:
    allowed-ips:  # 回调 IP 白名单（逗号分隔，支持 CIDR）
      - "101.226.62.0/24"    # 微信支付回调 IP 段
      - "208.78.164.0/22"    # 支付宝回调 IP 段
```

> 未配置时允许所有 IP（仅限开发环境）。生产环境务必配置。

### 回调处理流程

```
第三方支付平台
    │
    │  异步回调（带签名）
    ↓
CallbackIpWhitelistInterceptor  ─→  IP 白名单校验
    │
    ↓
PaymentStrategy.verifyCallback()  ─→  签名验证
    │
    ↓
PaymentStrategy.parseCallback()   ─→  解析回调数据
    │
    ↓
更新 PaymentOrder 状态
    │
    ├─ 订单支付 → 发布 MQ: payment.order.completed
    ├─ 充值到账 → 更新钱包余额 + 发布 MQ: payment.balance.changed
    └─ 退款完成 → 余额回滚 + 发布 MQ: payment.refund.completed
```

> 业务服务**不需要**处理第三方回调，所有回调由支付中台统一处理，结果通过 MQ 推送。

---

## 内部 API 安全机制

所有 `/internal/**` 端点受 `InternalApiFilter` 三层安全校验保护。

### 第一层：内网 IP 白名单

默认允许的网段：`127.0.0.1`、`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`。

> 生产环境如果经过 Nginx/网关代理，需配置 `payment.internal.trust-proxy=true` 以读取 `X-Forwarded-For` 头获取真实 IP。

### 第二层：静态令牌（X-Internal-Token）

每个请求必须携带 HTTP Header：

```
X-Internal-Token: <与支付中台一致的令牌>
```

> 令牌通过环境变量 `INTERNAL_TOKEN` 注入，必须与支付中台服务配置的值一致。

### 第三层：HMAC-SHA256 签名（可选）

配置 `payment.internal.sign-secret` 后自动启用。启用时需额外携带三个 Header：

| Header | 说明 |
|--------|------|
| `X-Timestamp` | 请求时间戳（毫秒），允许 ±5 分钟偏差 |
| `X-Nonce` | 唯一随机字符串（防重放，5 分钟内不可重复） |
| `X-Signature` | HMAC-SHA256 签名（hex 编码） |

**签名计算方式**：

```
data = HTTP_METHOD + "|" + requestURI + "|" + timestamp + "|" + nonce + "|" + requestBody
signature = HMAC-SHA256(data, signSecret) → hex string
```

**示例**：

```java
String data = "POST|/internal/payment/wallet/add-balance|1711065600000|abc123|{}";
String signature = HmacSHA256(data, signSecret); // → "a1b2c3..."
```

> 非 POST 请求的 `requestBody` 为空字符串。

### 推荐：实现 Feign 拦截器自动注入安全头

下游服务建议实现 `RequestInterceptor`，避免每个调用处手动处理：

```java
@Component
public class PaymentInternalApiInterceptor implements RequestInterceptor {

    @Value("${payment.internal.token:}")
    private String internalToken;

    @Value("${payment.internal.sign-secret:}")
    private String signSecret;

    @Override
    public void apply(RequestTemplate template) {
        // 注入静态令牌
        template.header("X-Internal-Token", internalToken);

        // 如果配置了签名密钥，注入 HMAC 签名头
        if (signSecret != null && !signSecret.isEmpty()) {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String nonce = UUID.randomUUID().toString();
            String body = template.body() != null ? new String(template.body(), StandardCharsets.UTF_8) : "";
            String data = template.method() + "|" + template.path() + "|" + timestamp + "|" + nonce + "|" + body;
            String signature = HmacSHA256.hex(data, signSecret);

            template.header("X-Timestamp", timestamp);
            template.header("X-Nonce", nonce);
            template.header("X-Signature", signature);
        }
    }
}
```

---

## MQ 消息消费指南

支付中台通过 RabbitMQ 发布事件消息，下游服务可按需订阅。

### Exchange 与 Queue 定义

| Exchange | 类型 | 说明 |
|----------|------|------|
| `payment.exchange` | Topic | 支付事件交换机 |

所有常量定义在 `PaymentMqConstants` 中，可直接引用。

### 消息类型

#### 1. 支付订单完成（`payment.order.completed`）

**Queue**：`payment.order.completed.queue`

```java
PaymentOrderCompletedMessage {
    String orderNo;          // 支付订单号
    String businessOrderNo;  // 关联业务订单号
    String customerNo;       // 用户编号
    Long   amountFen;        // 支付金额（分）
    String platform;         // 支付平台（WECHAT/ALIPAY/DOUYIN）
    String paidAt;           // 支付时间
    String bizType;          // 业务类型
    String messageId;        // 消息唯一ID（幂等键）
    String sign;             // HMAC-SHA256 签名
}
```

#### 2. 退款完成（`payment.refund.completed`）

**Queue**：`payment.refund.completed.queue`

```java
RefundCompletedMessage {
    String refundNo;         // 退款单号
    String orderNo;          // 原支付订单号
    String businessOrderNo;  // 关联业务订单号
    String customerNo;       // 用户编号
    Long   refundAmountFen;  // 退款金额（分）
    String status;           // 退款状态（SUCCESS/FAILED）
    String bizType;          // 业务类型
    String messageId;
    String sign;
}
```

#### 3. 提现完成（`payment.withdraw.completed`）

**Queue**：`payment.withdraw.completed.queue`

```java
WithdrawCompletedMessage {
    String requestNo;        // 提现申请号
    String customerNo;       // 用户编号
    Long   amountFen;        // 提现金额（分）
    Long   feeFen;           // 手续费（分）
    Long   actualAmountFen;  // 实际到账金额（分）
    String status;           // 提现状态（SUCCESS/FAILED）
    String bizType;          // 业务类型
    String messageId;
    String sign;
}
```

#### 4. 余额变动（`payment.balance.changed`）

**Queue**：`payment.balance.changed.queue`

```java
BalanceChangedMessage {
    String customerNo;       // 用户编号
    String walletNo;         // 钱包编号
    Long   beforeBalanceFen; // 变动前余额（分）
    Long   afterBalanceFen;  // 变动后余额（分）
    Long   changeAmountFen;  // 变动金额（分），正数增加、负数减少
    String transactionType;  // 交易类型
    String bizType;          // 业务类型
    String messageId;
    String sign;
}
```

#### 5. 支付订单取消（`payment.order.cancelled`）

**Queue**：`payment.order.cancelled.queue`

> 订单超时过期或用户主动取消时发布。

```java
PaymentOrderCompletedMessage {
    String orderNo;          // 支付订单号
    String businessOrderNo;  // 关联业务订单号
    String customerNo;       // 用户编号
    Long   amountFen;        // 订单金额（分）
    String platform;         // 支付平台
    String bizType;          // 业务类型
    String messageId;
    String sign;
}
```

#### 6. 钱包入账指令（`payment.wallet.credit`）

**Queue**：`payment.wallet.credit.queue`

> 通用钱包入账指令，由**业务系统发送、支付中台消费**。一条消息对应一笔入账操作。业务系统负责计算金额和决定入账对象，支付中台只执行"给谁加多少钱"的操作。
>
> 典型场景：飞手结算时，drone-system 发布两条消息——飞手收入记入飞手钱包，平台服务费记入平台钱包。

```java
WalletCreditMessage {
    String customerNo;        // 入账用户编号（必填）
    String bizType;           // 业务类型（可选，默认 DRONE_ORDER）
    Long   amountFen;         // 入账金额，单位：分（必填，> 0）
    String transactionType;   // 交易类型（必填，WalletTransactionType 枚举值）
    String description;       // 交易描述（必填）
    String referenceNo;       // 幂等键 / 关联单号（必填）
    String messageId;         // 消息唯一 ID
    String sign;              // HMAC-SHA256 签名
}
```

**transactionType 可选值**：`RECHARGE`、`WITHDRAW`、`PAYMENT`、`REFUND`、`RED_PACKET_CONVERT`、`FEE`、`PENALTY`、`PILOT_INCOME`、`PLATFORM_FEE`

### 消息总览

| # | Routing Key | Queue | 方向 | 说明 |
|---|-------------|-------|------|------|
| 1 | `payment.order.completed` | `payment.order.completed.queue` | 中台 → 业务 | 支付成功 |
| 2 | `payment.refund.completed` | `payment.refund.completed.queue` | 中台 → 业务 | 退款完成 |
| 3 | `payment.withdraw.completed` | `payment.withdraw.completed.queue` | 中台 → 业务 | 提现完成 |
| 4 | `payment.balance.changed` | `payment.balance.changed.queue` | 中台 → 业务 | 余额变动 |
| 5 | `payment.order.cancelled` | `payment.order.cancelled.queue` | 中台 → 业务 | 订单取消/过期 |
| 6 | `payment.wallet.credit` | `payment.wallet.credit.queue` | 业务 → 中台 | 钱包入账指令 |

### 消息验签

所有消息携带 `sign` 字段（HMAC-SHA256），消费前必须验签。使用 `MessageSignUtil` 工具类：

```java
// 支付订单消息验签
boolean valid = MessageSignUtil.verifyOrderMessage(
    message.getSign(),
    message.getMessageId(),
    message.getOrderNo(),
    message.getBusinessOrderNo(),
    message.getCustomerNo(),
    message.getAmountFen(),
    message.getPlatform(),
    message.getBizType()
);
```

> **密钥加载**：`MessageSignUtil` 按以下优先级加载密钥：环境变量 `MQ_SIGN_SECRET` → JVM 系统属性 `mq.sign.secret` → Spring 配置注入（`MessageSignUtil.setSecret(secret)`）。

### 幂等处理

每条消息包含唯一的 `messageId`，消费端应基于此字段做幂等判断，避免重复处理。

### 死信队列

每条业务队列均有对应的死信队列（DLQ），命名规则：`{queue-name}.dlq`。消费失败的消息会自动进入死信队列，可人工排查后重新投递。

### Spring Boot 消费配置示例

```java
@Configuration
public class PaymentMqConfig {

    @Value("${payment.mq.sign-secret:}")
    private String mqSignSecret;

    @PostConstruct
    public void init() {
        MessageSignUtil.setSecret(mqSignSecret);
    }

    @Bean
    public TopicExchange paymentExchange() {
        return new TopicExchange(PaymentMqConstants.PAYMENT_EXCHANGE);
    }

    @Bean
    public Queue orderCompletedQueue() {
        return QueueBuilder.durable(PaymentMqConstants.QUEUE_ORDER_COMPLETED)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", PaymentMqConstants.DLQ_ORDER_COMPLETED)
                .build();
    }

    @Bean
    public Binding orderCompletedBinding() {
        return BindingBuilder.bind(orderCompletedQueue())
                .to(paymentExchange())
                .with(PaymentMqConstants.RK_PAYMENT_ORDER_COMPLETED);
    }

    // 其他 queue/binding 按相同模式声明...
}
```

---

## 统一响应格式

所有接口返回 `Response<T>` 包装类型：

```json
{
  "code": 200,
  "message": "OK",
  "data": { ... },
  "timestamp": "2026-04-20 10:30:00"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | int | 业务码，`200` 表示成功 |
| `message` | String | 提示信息 |
| `data` | T | 响应数据，失败时为 `null` |
| `timestamp` | String | 响应时间（GMT+8） |

---

## 错误码参考

| code | 含义 |
|------|------|
| 200 | 成功 |
| 400 | 参数错误 |
| 403 | 安全校验失败（IP/Token/签名） |
| 500 | 服务内部错误 |
| 503 | 服务暂不可用（Sentinel 降级） |

业务异常通过 `BusinessException` 抛出，`code` 为非标值时请参考 `message` 字段中的具体描述。

---

## 定时任务

支付中台内置以下定时任务，影响业务行为：

| 调度器 | 执行频率 | 说明 |
|--------|---------|------|
| `PaymentOrderExpireScheduler` | 每 60 秒 | 扫描过期的 PENDING 订单并标记为 EXPIRED，每批 100 条 |
| `RefundStatusSyncScheduler` | 每 30 秒（可配置） | 主动查询第三方退款状态，同步 PROCESSING 状态的退款单 |
| `OutboxMessageScheduler` | 每 30 秒 | 重试发送失败的 MQ 消息（Outbox 模式），指数退避（30s → 60s → 120s → ...） |
| `WalletIntegrityScheduler` | 每天 02:13 | 全量钱包完整性校验，基于 `balance_hash` 检测异常 |

**可配置项**：

```yaml
payment:
  common:
    order-expire-minutes: 30           # 订单过期时间（分钟）
    refund-sync-enabled: true           # 是否启用退款状态同步
    refund-sync-delay-ms: 30000         # 同步间隔（毫秒）
    refund-sync-initial-delay-ms: 15000 # 启动延迟（毫秒）
    refund-sync-batch-size: 20          # 每批同步数量
```

---

## 部署与运维

### 基础设施依赖

| 依赖 | 用途 |
|------|------|
| PostgreSQL | 数据持久化（schema: `beenest_payment`） |
| Redis | Nonce 防重放、缓存 |
| RabbitMQ | MQ 消息投递 |
| Nacos | 服务注册与发现 |
| Sentinel | 熔断降级 |

### 关键环境变量

| 变量名 | 必填 | 说明 |
|--------|------|------|
| `PAYMENT_DB_URL` | 是 | PostgreSQL 连接地址 |
| `PAYMENT_DB_USERNAME` | 是 | 数据库用户名 |
| `PAYMENT_DB_PASSWORD` | 是 | 数据库密码 |
| `NACOS_ADDR` | 是 | Nacos 服务地址 |
| `RABBITMQ_HOST` | 是 | RabbitMQ 主机 |
| `RABBITMQ_PORT` | 是 | RabbitMQ 端口 |
| `RABBITMQ_USERNAME` | 是 | RabbitMQ 用户名 |
| `RABBITMQ_PASSWORD` | 是 | RabbitMQ 密码 |
| `WALLET_HASH_SECRET` | 是 | 钱包余额 HMAC 校验密钥 |
| `INTERNAL_TOKEN` | 是 | 内部 API 静态令牌 |
| `INTERNAL_SIGN_SECRET` | 否 | 内部 API HMAC 签名密钥 |
| `MQ_SIGN_SECRET` | 是 | MQ 消息验签密钥 |
| `PAYSCORE_WECHAT_ENABLED` | 否 | 启用微信支付分（默认 `false`） |
| `PAYSCORE_WECHAT_SERVICE_ID` | 条件必填 | 微信支付分服务ID |
| `PAYSCORE_WECHAT_NOTIFY_URL` | 条件必填 | 微信支付分授权回调地址 |
| `PAYSCORE_WECHAT_COMPLETE_NOTIFY_URL` | 否 | 微信支付分完结回调地址 |
| `PAYSCORE_ALIPAY_ENABLED` | 否 | 启用支付宝芝麻信用免押（默认 `false`） |
| `PAYSCORE_ALIPAY_PRODUCT_CODE` | 条件必填 | 芝麻信用产品码 |
| `PAYSCORE_ALIPAY_NOTIFY_URL` | 条件必填 | 支付宝芝麻信用回调地址 |

### Docker 部署

```bash
# 构建（多阶段构建，Eclipse Temurin JRE 21 基础镜像）
docker build -t beenest-payment .

# 运行
docker run -d \
  -e PAYMENT_DB_URL=jdbc:postgresql://db:5432/beenest_payment \
  -e NACOS_ADDR=nacos:8848 \
  -e RABBITMQ_HOST=rabbitmq \
  beenest-payment
```

---

## 常见问题

### Q: bizType 不传会怎样？

A: 默认使用 `DRONE_ORDER`。如果你的业务线不是无人机订单，请务必显式传入。

### Q: 支付订单创建后多久过期？

A: 默认 30 分钟，通过 `payment.common.order-expire-minutes` 配置。

### Q: Feign 调用返回 503 怎么办？

A: 表示支付中台服务不可用或触发了 Sentinel 熔断。`PaymentFeignFallbackFactory` 会自动降级并返回 503 响应。检查 Nacos 服务注册状态和 Sentinel 规则。

### Q: 钱包余额为负数正常吗？

A: 不正常。`deduct-balance` 接口在余额不足时返回 `data=false`，调用方应检查返回值。

### Q: 前端支付参数从哪来？

A: 调用 `createRechargeOrder` 或 `createOrderPayment` 后，返回的 `Map` 中 `paymentParams` 字段包含前端调起支付所需的全部参数，直接透传给前端即可。

### Q: 如何处理 MQ 消息消费失败？

A: 消费失败的消息会进入死信队列（DLQ）。可登录 RabbitMQ 管理台查看 DLQ 中的消息，排查问题后手动重新投递。

### Q: 什么时候需要调用冻结/解冻接口？

A: 一般情况下无需直接调用。提现流程由支付中台内部自动管理冻结→扣减→解冻的完整生命周期。仅在需要自定义担保/预授权场景时才需直接调用 `freezeBalance` / `unfreezeBalance`。

### Q: 充血模型的领域方法有哪些约束？

A: 聚合根的领域方法内部会校验状态转换合法性（如 `PaymentOrder.markAsPaid()` 只允许从 `PENDING` 转换到 `PAID`），非法转换会抛出 `BusinessException`。Service 层应使用这些领域方法代替手动 `setStatus()`。

### Q: 支付分和普通支付有什么区别？

A: 普通支付是「即时交易」——下单即扣款。支付分是「信用免押」——先冻结信用额度，服务结束后按实际费用扣款，剩余解冻。典型场景是商户入驻保证金免押：用户无需缴纳 1000 元押金，通过信用分担保即可入驻，入驻期满无违约则全额解冻。

### Q: 微信支付分和支付宝芝麻信用有什么差异？

A: 主要差异：(1) 微信支付分不做前端信用评估，免押判断在授权时由微信侧完成；支付宝芝麻信用通过查询芝麻分与阈值比较判断；(2) 微信支付分支持修改冻结金额（`modify`），支付宝不支持，需取消后重新创建；(3) 回调签名验证方式不同——微信用 SDK `NotificationParser`，支付宝用 `AlipaySignature.rsaCheckV1`。

### Q: 服务订单创建后多久过期？

A: 默认 30 分钟，通过 `payment.payscore.common.auth-expire-minutes` 配置。超时后状态变为 `EXPIRED`。

### Q: 完结时 actualAmount 传 0 会怎样？

A: 表示全额解冻——不扣取任何费用，冻结的信用额度全部释放。典型场景：商户入驻期满，无违约行为。

### Q: 如何处理支付宝芝麻信用不支持修改冻结金额？

A: 调用 `modify` 接口时会抛出 `BusinessException("支付宝芝麻信用暂不支持修改冻结金额，请取消当前订单后重新创建")`。业务侧需先 `cancel` 当前订单，再重新 `createServiceOrder`。
