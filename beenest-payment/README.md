# beenest-payment 支付中台接入文档

支付中台为 Beenest 旗下所有业务系统提供统一的支付、钱包、退款、提现能力。下游服务通过引入 `beenest-payment-api` 依赖，使用 Feign Client 调用内部 API。

> **适用版本**：`0.0.1-SNAPSHOT`（Java 21，Spring Boot 3.5.x）

---

## 目录

- [快速开始](#快速开始)
- [核心概念](#核心概念)
- [Feign 接口一览](#feign-接口一览)
- [内部 API 安全机制](#内部-api-安全机制)
- [MQ 消息消费指南](#mq-消息消费指南)
- [统一响应格式](#统一响应格式)
- [错误码参考](#错误码参考)
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
| `wxpay` | 微信小程序支付 |
| `alipay` | 支付宝小程序支付 |
| `toutiao` | 抖音小程序支付 |

**平台标识（platform）** — 前端运行环境：

| 值 | 说明 |
|----|------|
| `mp-weixin` | 微信小程序 |
| `mp-alipay` | 支付宝小程序 |
| `mp-toutiao` | 抖音小程序 |

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
| `planNo` | String | 是 | 业务计划编号 |
| `amount` | Long | 否 | 支付金额（分），不传时由服务端根据 planNo 计算 |
| `payType` | String | 是 | 支付方式：`wxpay`/`alipay`/`toutiao` |
| `platform` | String | 是 | 平台标识：`mp-weixin`/`mp-alipay`/`mp-toutiao` |
| `openid` | String | 条件必填 | 微信支付时必传 |
| `couponId` | Long | 否 | 优惠券 ID |
| `bizType` | String | 否 | 业务类型 |
| `originalAmount` | Long | 否 | 原始金额（分，前端展示用） |
| `discountAmount` | Long | 否 | 优惠金额（分） |

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

### 内部查询（供 drone-system 查询支付数据）

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/payment/latest-by-plan/{planNo}` | 根据计划编号查询最新支付订单 |
| `GET` | `/refund/list-by-order/{orderNo}` | 查询订单关联的所有退款记录 |
| `GET` | `/refund/latest-pending-by-order/{orderNo}` | 查询订单最新的待处理退款 |
| `GET` | `/wallet/transactions/raw/{customerNo}` | 分页查询用户原始交易记录 |
| `GET` | `/wallet/transactions/statistics/{customerNo}` | 查询用户收支统计（按时间段，支持 `startTime`/`endTime` 参数） |

### 管理端

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/admin/wallets` | 分页查询钱包列表 |
| `POST` | `/admin/transactions` | 分页查询交易流水 |
| `POST` | `/admin/reconciliation/tasks` | 分页查询对账任务 |
| `POST` | `/admin/reconciliation/create` | 创建对账任务（传 `date` 和 `channel`） |

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
