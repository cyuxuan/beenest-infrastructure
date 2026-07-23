
-- 设置 search_path 到 beenest_payment schema，所有后续对象默认创建在此 schema 下
SET search_path TO beenest_payment;

-- ============================================================
-- 此脚本合并了所有 Flyway 迁移（V1_0_0 ~ V1_0_13）的最终状态。
-- 新环境只需执行此脚本即可获得完整的表结构，无需再运行 Flyway。
-- ============================================================

-- ============================================================
-- 1. 充值/支付订单表
-- 整合: 基础表 + V1_0_0(biz_type, ext) + V1_0_3(uk_payment_order_no)
--        + V1_0_6(plan_no→biz_no) + V1_0_10(app_id)
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_payment_order (
    id                         BIGSERIAL       PRIMARY KEY,
    order_no                   VARCHAR(64)     NOT NULL,
    customer_no                VARCHAR(64)     NOT NULL,
    wallet_no                  VARCHAR(64)     NOT NULL,
    amount                     BIGINT          NOT NULL,
    platform                   VARCHAR(20)     NOT NULL,
    payment_method             VARCHAR(30)     NOT NULL,
    third_party_order_no       VARCHAR(128),
    third_party_transaction_no VARCHAR(128),
    payment_params             TEXT,
    callback_data              TEXT,
    status                     VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    paid_time                  TIMESTAMP,
    expire_time                TIMESTAMP       NOT NULL,
    notify_url                 VARCHAR(500),
    return_url                 VARCHAR(500),
    remark                     TEXT,
    biz_no                     VARCHAR(64),
    biz_type                   VARCHAR(32)     DEFAULT 'DRONE_ORDER',
    ext                        TEXT,
    app_id                     VARCHAR(32)     NOT NULL DEFAULT 'DRONE',
    create_time                TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time                TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_payment_order_no UNIQUE (order_no)
);

COMMENT ON TABLE ds_payment_order IS '充值订单表，记录充值订单信息和支付状态';
COMMENT ON COLUMN ds_payment_order.id IS '主键ID';
COMMENT ON COLUMN ds_payment_order.order_no IS '订单号，唯一标识';
COMMENT ON COLUMN ds_payment_order.customer_no IS '用户编号（通用化）';
COMMENT ON COLUMN ds_payment_order.wallet_no IS '钱包编号';
COMMENT ON COLUMN ds_payment_order.amount IS '充值金额（分）';
COMMENT ON COLUMN ds_payment_order.platform IS '支付平台: WECHAT/ALIPAY/DOUYIN';
COMMENT ON COLUMN ds_payment_order.payment_method IS '支付方式: WECHAT_MINI/ALIPAY_MINI/DOUYIN_MINI';
COMMENT ON COLUMN ds_payment_order.third_party_order_no IS '第三方订单号';
COMMENT ON COLUMN ds_payment_order.third_party_transaction_no IS '第三方交易号';
COMMENT ON COLUMN ds_payment_order.payment_params IS '支付参数，JSON格式';
COMMENT ON COLUMN ds_payment_order.callback_data IS '回调数据，JSON格式';
COMMENT ON COLUMN ds_payment_order.status IS '订单状态: PENDING/PAID/CANCELLED/EXPIRED/REFUNDED';
COMMENT ON COLUMN ds_payment_order.paid_time IS '支付完成时间';
COMMENT ON COLUMN ds_payment_order.expire_time IS '订单过期时间';
COMMENT ON COLUMN ds_payment_order.notify_url IS '支付结果通知地址';
COMMENT ON COLUMN ds_payment_order.return_url IS '支付完成跳转地址';
COMMENT ON COLUMN ds_payment_order.remark IS '备注信息';
COMMENT ON COLUMN ds_payment_order.biz_no IS '关联业务单号（通用化，由调用方传入）';
COMMENT ON COLUMN ds_payment_order.biz_type IS '业务类型标识（DRONE_ORDER, SHOP_ORDER等）';
COMMENT ON COLUMN ds_payment_order.ext IS '扩展字段JSON';
COMMENT ON COLUMN ds_payment_order.app_id IS '业务系统标识（DRONE/SHOP），用于多租户隔离';
COMMENT ON COLUMN ds_payment_order.create_time IS '创建时间';
COMMENT ON COLUMN ds_payment_order.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_payment_customer_no ON ds_payment_order(customer_no);
CREATE INDEX IF NOT EXISTS idx_payment_wallet_no ON ds_payment_order(wallet_no);
CREATE INDEX IF NOT EXISTS idx_payment_platform ON ds_payment_order(platform);
CREATE INDEX IF NOT EXISTS idx_payment_status ON ds_payment_order(status);
CREATE INDEX IF NOT EXISTS idx_payment_third_party_order ON ds_payment_order(third_party_order_no);
CREATE INDEX IF NOT EXISTS idx_payment_create_time ON ds_payment_order(create_time);
CREATE INDEX IF NOT EXISTS idx_payment_expire_time ON ds_payment_order(expire_time);
CREATE INDEX IF NOT EXISTS idx_payment_order_biz_no ON ds_payment_order(biz_no);
CREATE INDEX IF NOT EXISTS idx_payment_order_biz_type ON ds_payment_order(biz_type);
CREATE INDEX IF NOT EXISTS idx_payment_order_app_id ON ds_payment_order(app_id);

-- ============================================================
-- 2. 用户钱包表
-- 整合: 基础表 + V1_0_2(biz_type→已删除) + V1_0_9(customer_name/phone)
--        + V1_0_10(app_id) + V1_0_12(uk_wallet_customer_appid) + V1_0_13(删biz_type)
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_wallet (
    id             BIGSERIAL       PRIMARY KEY,
    wallet_no      VARCHAR(64)     NOT NULL,
    customer_no    VARCHAR(64)     NOT NULL,
    balance        BIGINT          NOT NULL DEFAULT 0,
    frozen_balance BIGINT          NOT NULL DEFAULT 0,
    total_recharge BIGINT          NOT NULL DEFAULT 0,
    total_withdraw BIGINT          NOT NULL DEFAULT 0,
    total_consume  BIGINT          NOT NULL DEFAULT 0,
    status         VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    version        INTEGER         NOT NULL DEFAULT 0,
    balance_hash   VARCHAR(128),
    customer_name  VARCHAR(128),
    customer_phone VARCHAR(32),
    app_id         VARCHAR(32)     NOT NULL DEFAULT 'DRONE',
    create_time    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wallet_no UNIQUE (wallet_no),
    CONSTRAINT uk_wallet_customer_appid UNIQUE (customer_no, app_id),
    CONSTRAINT chk_wallet_balance_nonneg CHECK (balance >= 0),
    CONSTRAINT chk_wallet_frozen_nonneg CHECK (frozen_balance >= 0),
    CONSTRAINT chk_wallet_totals_nonneg CHECK (total_recharge >= 0 AND total_withdraw >= 0 AND total_consume >= 0)
);

COMMENT ON TABLE ds_wallet IS '用户钱包表，存储用户钱包基本信息和余额';
COMMENT ON COLUMN ds_wallet.id IS '主键ID';
COMMENT ON COLUMN ds_wallet.wallet_no IS '钱包编号，唯一标识';
COMMENT ON COLUMN ds_wallet.customer_no IS '用户编号（通用化，不限于客户）';
COMMENT ON COLUMN ds_wallet.balance IS '可用余额（分）';
COMMENT ON COLUMN ds_wallet.frozen_balance IS '冻结余额（分）';
COMMENT ON COLUMN ds_wallet.total_recharge IS '累计充值金额（分）';
COMMENT ON COLUMN ds_wallet.total_withdraw IS '累计提现金额（分）';
COMMENT ON COLUMN ds_wallet.total_consume IS '累计消费金额（分）';
COMMENT ON COLUMN ds_wallet.status IS '钱包状态: ACTIVE(正常), FROZEN(冻结), CLOSED(关闭)';
COMMENT ON COLUMN ds_wallet.version IS '版本号，用于乐观锁控制';
COMMENT ON COLUMN ds_wallet.balance_hash IS '余额哈希校验值（HMAC-SHA256），用于检测余额是否被直接篡改';
COMMENT ON COLUMN ds_wallet.customer_name IS '客户姓名（从 CAS 属性同步）';
COMMENT ON COLUMN ds_wallet.customer_phone IS '客户手机号（从 CAS 属性同步）';
COMMENT ON COLUMN ds_wallet.app_id IS '业务系统标识（DRONE/SHOP），用于多租户隔离';
COMMENT ON COLUMN ds_wallet.create_time IS '创建时间';
COMMENT ON COLUMN ds_wallet.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_wallet_customer_no ON ds_wallet(customer_no);
CREATE INDEX IF NOT EXISTS idx_wallet_status ON ds_wallet(status);
CREATE INDEX IF NOT EXISTS idx_wallet_update_time ON ds_wallet(update_time);
CREATE INDEX IF NOT EXISTS idx_wallet_app_id ON ds_wallet(app_id);
CREATE INDEX IF NOT EXISTS idx_wallet_customer_phone ON ds_wallet(customer_phone);

-- ============================================================
-- 3. 钱包交易流水表
-- 整合: 基础表 + V1_0_2(biz_type→已删除) + V1_0_7(reference_no唯一索引)
--        + V1_0_10(app_id) + V1_0_13(删biz_type)
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_wallet_transaction (
    id               BIGSERIAL       PRIMARY KEY,
    transaction_no   VARCHAR(64)     NOT NULL,
    wallet_no        VARCHAR(64)     NOT NULL,
    customer_no      VARCHAR(64)     NOT NULL,
    transaction_type VARCHAR(30)     NOT NULL,
    amount           BIGINT          NOT NULL,
    before_balance   BIGINT          NOT NULL,
    after_balance    BIGINT          NOT NULL,
    description      VARCHAR(200)    NOT NULL,
    reference_no     VARCHAR(64),
    reference_type   VARCHAR(30),
    status           VARCHAR(20)     NOT NULL DEFAULT 'SUCCESS',
    remark           TEXT,
    app_id           VARCHAR(32)     NOT NULL DEFAULT 'DRONE',
    create_time      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_transaction_no UNIQUE (transaction_no),
    CONSTRAINT chk_transaction_amount_nonzero CHECK (amount != 0)
);

COMMENT ON TABLE ds_wallet_transaction IS '钱包交易流水表，记录所有资金变动';
COMMENT ON COLUMN ds_wallet_transaction.id IS '主键ID';
COMMENT ON COLUMN ds_wallet_transaction.transaction_no IS '交易流水号，唯一标识';
COMMENT ON COLUMN ds_wallet_transaction.wallet_no IS '钱包编号';
COMMENT ON COLUMN ds_wallet_transaction.customer_no IS '用户编号（通用化）';
COMMENT ON COLUMN ds_wallet_transaction.transaction_type IS '交易类型: RECHARGE/WITHDRAW/PAYMENT/REFUND/RED_PACKET_CONVERT/FEE/PENALTY';
COMMENT ON COLUMN ds_wallet_transaction.amount IS '交易金额（分），正数表示收入，负数表示支出';
COMMENT ON COLUMN ds_wallet_transaction.before_balance IS '交易前余额（分）';
COMMENT ON COLUMN ds_wallet_transaction.after_balance IS '交易后余额（分）';
COMMENT ON COLUMN ds_wallet_transaction.description IS '交易描述';
COMMENT ON COLUMN ds_wallet_transaction.reference_no IS '关联单号，如订单号、红包编号等';
COMMENT ON COLUMN ds_wallet_transaction.reference_type IS '关联类型: ORDER/RED_PACKET/COUPON等';
COMMENT ON COLUMN ds_wallet_transaction.status IS '交易状态: SUCCESS/FAILED/PROCESSING';
COMMENT ON COLUMN ds_wallet_transaction.remark IS '备注信息';
COMMENT ON COLUMN ds_wallet_transaction.app_id IS '业务系统标识（DRONE/SHOP），用于多租户隔离';
COMMENT ON COLUMN ds_wallet_transaction.create_time IS '创建时间';

CREATE INDEX IF NOT EXISTS idx_transaction_wallet_no ON ds_wallet_transaction(wallet_no);
CREATE INDEX IF NOT EXISTS idx_transaction_customer_no ON ds_wallet_transaction(customer_no);
CREATE INDEX IF NOT EXISTS idx_transaction_type ON ds_wallet_transaction(transaction_type);
CREATE INDEX IF NOT EXISTS idx_transaction_reference ON ds_wallet_transaction(reference_no, reference_type);
CREATE INDEX IF NOT EXISTS idx_transaction_create_time ON ds_wallet_transaction(create_time);
CREATE INDEX IF NOT EXISTS idx_transaction_status ON ds_wallet_transaction(status);
CREATE INDEX IF NOT EXISTS idx_wallet_transaction_app_id ON ds_wallet_transaction(app_id);
-- 幂等保护：防止同一关联单号重复入账（V1_0_7）
CREATE UNIQUE INDEX IF NOT EXISTS uk_wallet_transaction_reference_no
    ON ds_wallet_transaction (reference_no)
    WHERE reference_no IS NOT NULL AND reference_no != '';
COMMENT ON INDEX uk_wallet_transaction_reference_no IS '幂等保护：防止同一关联单号重复入账';

-- ============================================================
-- 4. 提现申请表
-- 整合: 基础表 + V1_0_2(biz_type→已删除) + V1_0_3(PROCESSING_ERROR)
--        + V1_0_10(app_id) + V1_0_13(删biz_type)
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_withdraw_request (
    id                   BIGSERIAL       PRIMARY KEY,
    request_no           VARCHAR(64)     NOT NULL,
    customer_no          VARCHAR(64)     NOT NULL,
    wallet_no            VARCHAR(64)     NOT NULL,
    amount               BIGINT          NOT NULL,
    withdraw_type        VARCHAR(20)     NOT NULL,
    account_type         VARCHAR(30)     NOT NULL,
    account_name         VARCHAR(100)    NOT NULL,
    account_number       VARCHAR(100)    NOT NULL,
    bank_name            VARCHAR(100),
    bank_branch          VARCHAR(200),
    fee_amount           BIGINT          NOT NULL DEFAULT 0,
    actual_amount        BIGINT          NOT NULL,
    status               VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    audit_user           VARCHAR(64),
    audit_time           TIMESTAMP,
    audit_remark         TEXT,
    process_time         TIMESTAMP,
    process_result       TEXT,
    third_party_order_no VARCHAR(128),
    remark               TEXT,
    app_id               VARCHAR(32)     NOT NULL DEFAULT 'DRONE',
    create_time          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_request_no UNIQUE (request_no),
    CONSTRAINT chk_withdraw_status CHECK (status IN ('PENDING', 'MANUAL_REVIEW', 'APPROVED', 'PROCESSING', 'SUCCESS', 'FAILED', 'CANCELLED', 'REJECTED', 'PROCESSING_ERROR'))
);

COMMENT ON TABLE ds_withdraw_request IS '提现申请表，记录用户提现申请和处理状态';
COMMENT ON COLUMN ds_withdraw_request.id IS '主键ID';
COMMENT ON COLUMN ds_withdraw_request.request_no IS '提现申请号，唯一标识';
COMMENT ON COLUMN ds_withdraw_request.customer_no IS '用户编号';
COMMENT ON COLUMN ds_withdraw_request.wallet_no IS '钱包编号';
COMMENT ON COLUMN ds_withdraw_request.amount IS '提现金额（分）';
COMMENT ON COLUMN ds_withdraw_request.withdraw_type IS '提现类型: ALIPAY/BANK_CARD/WECHAT/DOUYIN';
COMMENT ON COLUMN ds_withdraw_request.account_type IS '账户类型: PERSONAL/COMPANY';
COMMENT ON COLUMN ds_withdraw_request.account_name IS '账户姓名/企业名称';
COMMENT ON COLUMN ds_withdraw_request.account_number IS '账户号码';
COMMENT ON COLUMN ds_withdraw_request.bank_name IS '银行名称（银行卡提现时必填）';
COMMENT ON COLUMN ds_withdraw_request.bank_branch IS '开户行支行';
COMMENT ON COLUMN ds_withdraw_request.fee_amount IS '手续费金额（分）';
COMMENT ON COLUMN ds_withdraw_request.actual_amount IS '实际到账金额（分）';
COMMENT ON COLUMN ds_withdraw_request.status IS '申请状态: PENDING/MANUAL_REVIEW/APPROVED/PROCESSING/SUCCESS/FAILED/CANCELLED/REJECTED/PROCESSING_ERROR';
COMMENT ON COLUMN ds_withdraw_request.audit_user IS '审核人';
COMMENT ON COLUMN ds_withdraw_request.audit_time IS '审核时间';
COMMENT ON COLUMN ds_withdraw_request.audit_remark IS '审核备注';
COMMENT ON COLUMN ds_withdraw_request.process_time IS '处理完成时间';
COMMENT ON COLUMN ds_withdraw_request.process_result IS '处理结果';
COMMENT ON COLUMN ds_withdraw_request.third_party_order_no IS '第三方订单号';
COMMENT ON COLUMN ds_withdraw_request.remark IS '备注信息';
COMMENT ON COLUMN ds_withdraw_request.app_id IS '业务系统标识（DRONE/SHOP），用于多租户隔离';
COMMENT ON COLUMN ds_withdraw_request.create_time IS '创建时间';
COMMENT ON COLUMN ds_withdraw_request.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_withdraw_customer_no ON ds_withdraw_request(customer_no);
CREATE INDEX IF NOT EXISTS idx_withdraw_wallet_no ON ds_withdraw_request(wallet_no);
CREATE INDEX IF NOT EXISTS idx_withdraw_status ON ds_withdraw_request(status);
CREATE INDEX IF NOT EXISTS idx_withdraw_create_time ON ds_withdraw_request(create_time);
CREATE INDEX IF NOT EXISTS idx_withdraw_audit_time ON ds_withdraw_request(audit_time);
CREATE INDEX IF NOT EXISTS idx_withdraw_request_app_id ON ds_withdraw_request(app_id);

-- ============================================================
-- 5. 支付回调日志表
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_payment_callback_log (
    id             BIGSERIAL       PRIMARY KEY,
    order_no       VARCHAR(64)     NOT NULL,
    platform       VARCHAR(20)     NOT NULL,
    callback_type  VARCHAR(30)     NOT NULL,
    callback_data  TEXT            NOT NULL,
    signature      VARCHAR(500),
    verify_result  BOOLEAN,
    process_result VARCHAR(20),
    error_message  TEXT,
    create_time    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_payment_callback_log IS '支付回调日志表，记录第三方支付平台回调信息';
COMMENT ON COLUMN ds_payment_callback_log.id IS '主键ID';
COMMENT ON COLUMN ds_payment_callback_log.order_no IS '订单号';
COMMENT ON COLUMN ds_payment_callback_log.platform IS '支付平台';
COMMENT ON COLUMN ds_payment_callback_log.callback_type IS '回调类型: PAYMENT(支付回调), REFUND(退款回调)';
COMMENT ON COLUMN ds_payment_callback_log.callback_data IS '回调数据，原始JSON';
COMMENT ON COLUMN ds_payment_callback_log.signature IS '签名信息';
COMMENT ON COLUMN ds_payment_callback_log.verify_result IS '签名验证结果';
COMMENT ON COLUMN ds_payment_callback_log.process_result IS '处理结果: SUCCESS(成功), FAILED(失败), IGNORED(忽略)';
COMMENT ON COLUMN ds_payment_callback_log.error_message IS '错误信息';
COMMENT ON COLUMN ds_payment_callback_log.create_time IS '创建时间';

CREATE INDEX IF NOT EXISTS idx_callback_order_no ON ds_payment_callback_log(order_no);
CREATE INDEX IF NOT EXISTS idx_callback_platform ON ds_payment_callback_log(platform);
CREATE INDEX IF NOT EXISTS idx_callback_create_time ON ds_payment_callback_log(create_time);

-- ============================================================
-- 6. 支付事件日志表
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_payment_event (
    id               BIGSERIAL       PRIMARY KEY,
    event_no         VARCHAR(64)     NOT NULL,
    order_no         VARCHAR(64),
    event_type       VARCHAR(30)     NOT NULL,
    channel          VARCHAR(20)     NOT NULL,
    status           VARCHAR(20)     NOT NULL,
    request_content  TEXT,
    response_content TEXT,
    create_time      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_event_no UNIQUE (event_no)
);

COMMENT ON TABLE ds_payment_event IS '支付事件日志表';
COMMENT ON COLUMN ds_payment_event.id IS '主键ID';
COMMENT ON COLUMN ds_payment_event.event_no IS '事件编号唯一';
COMMENT ON COLUMN ds_payment_event.order_no IS '关联订单号';
COMMENT ON COLUMN ds_payment_event.event_type IS '事件类型: CALLBACK/NOTIFY/REFUND_NOTIFY';
COMMENT ON COLUMN ds_payment_event.channel IS '支付渠道';
COMMENT ON COLUMN ds_payment_event.status IS '状态: PENDING/SUCCESS/FAILED';
COMMENT ON COLUMN ds_payment_event.request_content IS '请求内容';
COMMENT ON COLUMN ds_payment_event.response_content IS '响应内容';
COMMENT ON COLUMN ds_payment_event.create_time IS '创建时间';
COMMENT ON COLUMN ds_payment_event.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_event_order_no ON ds_payment_event(order_no);
CREATE INDEX IF NOT EXISTS idx_event_type ON ds_payment_event(event_type);
CREATE INDEX IF NOT EXISTS idx_event_channel ON ds_payment_event(channel);
CREATE INDEX IF NOT EXISTS idx_event_create_time ON ds_payment_event(create_time);

-- ============================================================
-- 7. 风控规则表
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_payment_risk_rule (
    id          BIGSERIAL       PRIMARY KEY,
    rule_code   VARCHAR(64)     NOT NULL,
    rule_name   VARCHAR(100)    NOT NULL,
    rule_type   VARCHAR(20)     NOT NULL,
    threshold   BIGINT          NOT NULL DEFAULT 0,
    time_window INTEGER         NOT NULL DEFAULT 0,
    action      VARCHAR(20)     NOT NULL DEFAULT 'REJECT',
    is_enable   BOOLEAN         NOT NULL DEFAULT TRUE,
    create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_risk_rule_code UNIQUE (rule_code)
);

COMMENT ON TABLE ds_payment_risk_rule IS '支付风控规则表';
COMMENT ON COLUMN ds_payment_risk_rule.id IS '主键ID';
COMMENT ON COLUMN ds_payment_risk_rule.rule_code IS '规则编码唯一';
COMMENT ON COLUMN ds_payment_risk_rule.rule_name IS '规则名称';
COMMENT ON COLUMN ds_payment_risk_rule.rule_type IS '规则类型: LIMIT/FREQUENCY/BLACKLIST';
COMMENT ON COLUMN ds_payment_risk_rule.threshold IS '阈值';
COMMENT ON COLUMN ds_payment_risk_rule.time_window IS '时间窗口（秒）';
COMMENT ON COLUMN ds_payment_risk_rule.action IS '触发动作: REJECT/REVIEW/ALERT';
COMMENT ON COLUMN ds_payment_risk_rule.is_enable IS '是否启用';
COMMENT ON COLUMN ds_payment_risk_rule.create_time IS '创建时间';
COMMENT ON COLUMN ds_payment_risk_rule.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_risk_rule_type ON ds_payment_risk_rule(rule_type);
CREATE INDEX IF NOT EXISTS idx_risk_rule_enable ON ds_payment_risk_rule(is_enable);

-- ============================================================
-- 8. 退款记录表
-- 整合: 基础表 + V1_0_0(refund_policy, request_source, applicant_id, channel_status)
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_refund (
    id                    BIGSERIAL       PRIMARY KEY,
    refund_no             VARCHAR(64)     NOT NULL,
    order_no              VARCHAR(64)     NOT NULL,
    amount                BIGINT          NOT NULL,
    reason                VARCHAR(500),
    status                VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    third_party_refund_no VARCHAR(128),
    audit_user            VARCHAR(64),
    audit_time            TIMESTAMP,
    audit_remark          VARCHAR(500),
    create_time           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    refund_policy         VARCHAR(32),
    request_source        VARCHAR(32),
    applicant_id          VARCHAR(64),
    channel_status        VARCHAR(32),
    CONSTRAINT uk_refund_no UNIQUE (refund_no)
);

COMMENT ON TABLE ds_refund IS '退款记录表';
COMMENT ON COLUMN ds_refund.id IS '主键ID';
COMMENT ON COLUMN ds_refund.refund_no IS '退款编号唯一';
COMMENT ON COLUMN ds_refund.order_no IS '关联订单号';
COMMENT ON COLUMN ds_refund.amount IS '退款金额（分）';
COMMENT ON COLUMN ds_refund.reason IS '退款原因';
COMMENT ON COLUMN ds_refund.status IS '退款状态: PENDING/APPROVED/SUCCESS/FAILED';
COMMENT ON COLUMN ds_refund.third_party_refund_no IS '第三方退款单号';
COMMENT ON COLUMN ds_refund.audit_user IS '审核人';
COMMENT ON COLUMN ds_refund.audit_time IS '审核时间';
COMMENT ON COLUMN ds_refund.audit_remark IS '审核备注';
COMMENT ON COLUMN ds_refund.create_time IS '创建时间';
COMMENT ON COLUMN ds_refund.update_time IS '更新时间';
COMMENT ON COLUMN ds_refund.refund_policy IS '退款策略: AUTO_REFUND/MANUAL_REVIEW/NOT_ALLOWED';
COMMENT ON COLUMN ds_refund.request_source IS '申请来源: CUSTOMER_CANCEL/ADMIN/AUTO';
COMMENT ON COLUMN ds_refund.applicant_id IS '申请人';
COMMENT ON COLUMN ds_refund.channel_status IS '渠道退款状态';

CREATE INDEX IF NOT EXISTS idx_refund_order_no ON ds_refund(order_no);
CREATE INDEX IF NOT EXISTS idx_refund_status ON ds_refund(status);
CREATE INDEX IF NOT EXISTS idx_refund_create_time ON ds_refund(create_time);
CREATE INDEX IF NOT EXISTS idx_refund_policy ON ds_refund(refund_policy);
CREATE INDEX IF NOT EXISTS idx_refund_request_source ON ds_refund(request_source);

-- ============================================================
-- 9. 支付渠道配置表
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_payment_channel_config (
    id           BIGSERIAL       PRIMARY KEY,
    channel_name VARCHAR(50)     NOT NULL,
    channel_code VARCHAR(20)     NOT NULL,
    app_id       VARCHAR(100),
    merchant_id  VARCHAR(100),
    public_key   TEXT,
    private_key  TEXT,
    notify_url   VARCHAR(255),
    is_enable    BOOLEAN         NOT NULL DEFAULT TRUE,
    create_time  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_channel_code UNIQUE (channel_code)
);

COMMENT ON TABLE ds_payment_channel_config IS '支付渠道配置表';
COMMENT ON COLUMN ds_payment_channel_config.id IS '主键ID';
COMMENT ON COLUMN ds_payment_channel_config.channel_name IS '渠道名称';
COMMENT ON COLUMN ds_payment_channel_config.channel_code IS '渠道编码唯一: WECHAT/ALIPAY/DOUYIN';
COMMENT ON COLUMN ds_payment_channel_config.app_id IS '应用ID';
COMMENT ON COLUMN ds_payment_channel_config.merchant_id IS '商户号';
COMMENT ON COLUMN ds_payment_channel_config.public_key IS '公钥';
COMMENT ON COLUMN ds_payment_channel_config.private_key IS '私钥';
COMMENT ON COLUMN ds_payment_channel_config.notify_url IS '回调通知地址';
COMMENT ON COLUMN ds_payment_channel_config.is_enable IS '是否启用';
COMMENT ON COLUMN ds_payment_channel_config.create_time IS '创建时间';
COMMENT ON COLUMN ds_payment_channel_config.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_config_channel_code ON ds_payment_channel_config(channel_code);

-- ============================================================
-- 10. MQ消息Outbox表 (V1_0_4)
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_payment_outbox (
    id              BIGSERIAL       PRIMARY KEY,
    message_id      VARCHAR(64)     NOT NULL,
    exchange        VARCHAR(128),
    routing_key     VARCHAR(128)    NOT NULL,
    payload         TEXT            NOT NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    retry_count     INT             NOT NULL DEFAULT 0,
    max_retry       INT             NOT NULL DEFAULT 5,
    next_retry_time TIMESTAMP,
    error_message   TEXT,
    create_time     TIMESTAMP       NOT NULL DEFAULT NOW(),
    update_time     TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_outbox_message_id UNIQUE (message_id),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

COMMENT ON TABLE ds_payment_outbox IS 'MQ消息Outbox表，保证消息发送最终一致性';
COMMENT ON COLUMN ds_payment_outbox.id IS '主键ID';
COMMENT ON COLUMN ds_payment_outbox.message_id IS '消息唯一ID，用于幂等防重';
COMMENT ON COLUMN ds_payment_outbox.exchange IS 'MQ交换机';
COMMENT ON COLUMN ds_payment_outbox.routing_key IS 'MQ路由键';
COMMENT ON COLUMN ds_payment_outbox.payload IS '消息体';
COMMENT ON COLUMN ds_payment_outbox.status IS 'PENDING-待发送, SENT-已发送, FAILED-发送失败';
COMMENT ON COLUMN ds_payment_outbox.retry_count IS '当前重试次数';
COMMENT ON COLUMN ds_payment_outbox.max_retry IS '最大重试次数';
COMMENT ON COLUMN ds_payment_outbox.next_retry_time IS '下次重试时间，指数退避';
COMMENT ON COLUMN ds_payment_outbox.error_message IS '最后错误信息';
COMMENT ON COLUMN ds_payment_outbox.create_time IS '创建时间';
COMMENT ON COLUMN ds_payment_outbox.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_outbox_status_next_retry ON ds_payment_outbox(status, next_retry_time);

-- ============================================================
-- 11. 支付对账任务表
-- 整合: 基础表 + V1_0_5(platform_order_count, platform_amount, detail)
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_payment_reconciliation_task (
    id                   BIGSERIAL       PRIMARY KEY,
    date                 VARCHAR(10)     NOT NULL,
    channel              VARCHAR(20)     NOT NULL,
    status               VARCHAR(20)     NOT NULL,
    total_orders         INT             NOT NULL DEFAULT 0,
    total_amount         BIGINT          NOT NULL DEFAULT 0,
    platform_order_count INT,
    platform_amount      BIGINT,
    match_count          INT             NOT NULL DEFAULT 0,
    mismatch_count       INT             NOT NULL DEFAULT 0,
    detail               TEXT,
    create_time          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_recon_date_channel UNIQUE (date, channel)
);

COMMENT ON TABLE ds_payment_reconciliation_task IS '支付对账任务表';
COMMENT ON COLUMN ds_payment_reconciliation_task.id IS '主键ID';
COMMENT ON COLUMN ds_payment_reconciliation_task.date IS '对账日期(yyyy-MM-dd)';
COMMENT ON COLUMN ds_payment_reconciliation_task.channel IS '支付渠道: ALIPAY/WECHAT/DOUYIN';
COMMENT ON COLUMN ds_payment_reconciliation_task.status IS '对账状态: PENDING/PROCESSING/COMPLETED/MISMATCH/FAILED';
COMMENT ON COLUMN ds_payment_reconciliation_task.total_orders IS '本地订单数';
COMMENT ON COLUMN ds_payment_reconciliation_task.total_amount IS '本地总金额（分）';
COMMENT ON COLUMN ds_payment_reconciliation_task.platform_order_count IS '平台账单笔数（第三方返回的交易记录数）';
COMMENT ON COLUMN ds_payment_reconciliation_task.platform_amount IS '平台账单金额（分，第三方返回的交易总额）';
COMMENT ON COLUMN ds_payment_reconciliation_task.match_count IS '匹配数';
COMMENT ON COLUMN ds_payment_reconciliation_task.mismatch_count IS '不匹配数';
COMMENT ON COLUMN ds_payment_reconciliation_task.detail IS '不匹配明细（JSON数组）';
COMMENT ON COLUMN ds_payment_reconciliation_task.create_time IS '创建时间';
COMMENT ON COLUMN ds_payment_reconciliation_task.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_recon_date ON ds_payment_reconciliation_task(date);
CREATE INDEX IF NOT EXISTS idx_recon_channel ON ds_payment_reconciliation_task(channel);
CREATE INDEX IF NOT EXISTS idx_recon_status ON ds_payment_reconciliation_task(status);

-- ============================================================
-- 12. 钱包余额一致性校验日志表
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_wallet_integrity_log (
    id               BIGSERIAL       PRIMARY KEY,
    wallet_no        VARCHAR(64)     NOT NULL,
    customer_no      VARCHAR(64)     NOT NULL,
    expected_balance BIGINT          NOT NULL,
    actual_balance   BIGINT          NOT NULL,
    expected_frozen  BIGINT          NOT NULL,
    actual_frozen    BIGINT          NOT NULL,
    hash_valid       BOOLEAN,
    status           VARCHAR(20)     NOT NULL DEFAULT 'DETECTED',
    detail           TEXT,
    create_time      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_wallet_integrity_log IS '钱包余额一致性校验日志表';
COMMENT ON COLUMN ds_wallet_integrity_log.id IS '主键ID';
COMMENT ON COLUMN ds_wallet_integrity_log.wallet_no IS '钱包编号';
COMMENT ON COLUMN ds_wallet_integrity_log.customer_no IS '用户编号';
COMMENT ON COLUMN ds_wallet_integrity_log.expected_balance IS '期望可用余额（分），基于流水计算';
COMMENT ON COLUMN ds_wallet_integrity_log.actual_balance IS '实际可用余额（分），数据库中的值';
COMMENT ON COLUMN ds_wallet_integrity_log.expected_frozen IS '期望冻结余额（分）';
COMMENT ON COLUMN ds_wallet_integrity_log.actual_frozen IS '实际冻结余额（分）';
COMMENT ON COLUMN ds_wallet_integrity_log.hash_valid IS '哈希校验是否通过';
COMMENT ON COLUMN ds_wallet_integrity_log.status IS '状态: DETECTED(已检测), RECONCILED(已修复), IGNORED(已忽略)';
COMMENT ON COLUMN ds_wallet_integrity_log.detail IS '详细信息';
COMMENT ON COLUMN ds_wallet_integrity_log.create_time IS '创建时间';

CREATE INDEX IF NOT EXISTS idx_integrity_wallet_no ON ds_wallet_integrity_log(wallet_no);
CREATE INDEX IF NOT EXISTS idx_integrity_status ON ds_wallet_integrity_log(status);
CREATE INDEX IF NOT EXISTS idx_integrity_create_time ON ds_wallet_integrity_log(create_time);

-- ============================================================
-- 13. 支付分服务订单表（信用免押）(V1_0_8 + V1_0_10)
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_service_order (
    id                      BIGSERIAL PRIMARY KEY,
    order_no                VARCHAR(64) NOT NULL UNIQUE,
    biz_no                  VARCHAR(64),
    biz_type                VARCHAR(32) NOT NULL DEFAULT 'MERCHANT_DEPOSIT',
    customer_no             VARCHAR(64) NOT NULL,
    platform                VARCHAR(32) NOT NULL,
    service_id              VARCHAR(64) NOT NULL,
    deposit_amount          BIGINT NOT NULL,
    frozen_amount           BIGINT,
    actual_amount           BIGINT,
    status                  VARCHAR(32) NOT NULL DEFAULT 'PENDING_AUTH',
    auth_time               TIMESTAMP,
    complete_time           TIMESTAMP,
    expire_time             TIMESTAMP,
    third_party_order_no    VARCHAR(128),
    callback_data           TEXT,
    ext                     TEXT,
    remark                  VARCHAR(500),
    notify_url              VARCHAR(256),
    app_id                  VARCHAR(32) NOT NULL DEFAULT 'DRONE',
    create_time             TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time             TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE ds_service_order IS '支付分服务订单表（信用免押）';
COMMENT ON COLUMN ds_service_order.order_no IS '服务订单号';
COMMENT ON COLUMN ds_service_order.biz_no IS '关联业务单号（入驻申请编号）';
COMMENT ON COLUMN ds_service_order.biz_type IS '业务类型（MERCHANT_DEPOSIT=商户保证金）';
COMMENT ON COLUMN ds_service_order.customer_no IS '用户/商户编号';
COMMENT ON COLUMN ds_service_order.platform IS '支付分平台（WECHAT_PAYSCORE/ALIPAY_ZHIMA）';
COMMENT ON COLUMN ds_service_order.service_id IS '支付分服务ID';
COMMENT ON COLUMN ds_service_order.deposit_amount IS '原始保证金金额（分）';
COMMENT ON COLUMN ds_service_order.frozen_amount IS '实际冻结金额（分）';
COMMENT ON COLUMN ds_service_order.actual_amount IS '完结实际扣款金额（分，0表示全额解冻）';
COMMENT ON COLUMN ds_service_order.status IS '服务订单状态';
COMMENT ON COLUMN ds_service_order.third_party_order_no IS '第三方服务订单号';
COMMENT ON COLUMN ds_service_order.app_id IS '业务系统标识（DRONE/SHOP），用于多租户隔离';

CREATE INDEX IF NOT EXISTS idx_service_order_customer ON ds_service_order(customer_no);
CREATE INDEX IF NOT EXISTS idx_service_order_biz_no ON ds_service_order(biz_no);
CREATE INDEX IF NOT EXISTS idx_service_order_status ON ds_service_order(status);
CREATE INDEX IF NOT EXISTS idx_service_order_platform ON ds_service_order(platform);
CREATE INDEX IF NOT EXISTS idx_service_order_app_id ON ds_service_order(app_id);

-- ============================================================
-- 14. 信用授权记录表 (V1_0_8 + V1_0_13)
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_credit_authorization (
    id                      BIGSERIAL PRIMARY KEY,
    auth_no                 VARCHAR(64) NOT NULL UNIQUE,
    order_no                VARCHAR(64) NOT NULL,
    customer_no             VARCHAR(64) NOT NULL,
    platform                VARCHAR(32) NOT NULL,
    credit_score            INTEGER,
    deposit_amount          BIGINT NOT NULL,
    exemption_result        VARCHAR(32),
    frozen_amount           BIGINT,
    auth_status             VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    auth_time               TIMESTAMP,
    expire_time             TIMESTAMP,
    third_party_auth_no     VARCHAR(128),
    app_id                  VARCHAR(32) NOT NULL DEFAULT 'DRONE',
    create_time             TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time             TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE ds_credit_authorization IS '信用授权记录表';
COMMENT ON COLUMN ds_credit_authorization.auth_no IS '授权编号';
COMMENT ON COLUMN ds_credit_authorization.order_no IS '关联服务订单号';
COMMENT ON COLUMN ds_credit_authorization.credit_score IS '用户信用分';
COMMENT ON COLUMN ds_credit_authorization.deposit_amount IS '保证金金额';
COMMENT ON COLUMN ds_credit_authorization.exemption_result IS '免押结果（FULL_EXEMPT/PARTIAL_EXEMPT/NOT_EXEMPT）';
COMMENT ON COLUMN ds_credit_authorization.frozen_amount IS '实际冻结金额';
COMMENT ON COLUMN ds_credit_authorization.auth_status IS '授权状态';
COMMENT ON COLUMN ds_credit_authorization.app_id IS '业务系统标识（DRONE/SHOP），用于多租户隔离';

CREATE INDEX IF NOT EXISTS idx_credit_auth_order ON ds_credit_authorization(order_no);
CREATE INDEX IF NOT EXISTS idx_credit_auth_customer ON ds_credit_authorization(customer_no);
CREATE INDEX IF NOT EXISTS idx_credit_authorization_app_id ON ds_credit_authorization(app_id);

-- ============================================================
-- 15. 应用凭证表 (V1_0_11)
-- 每个业务系统拥有独立的认证密钥，替代全局共享密钥
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_app_credential (
    id               BIGSERIAL       PRIMARY KEY,
    app_id           VARCHAR(32)     NOT NULL,
    app_name         VARCHAR(64)     NOT NULL,
    app_secret       VARCHAR(128)    NOT NULL,
    mq_secret        VARCHAR(128)    NOT NULL,
    allowed_networks TEXT,
    status           VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    description      VARCHAR(256),
    created_by       VARCHAR(64),
    create_time      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by       VARCHAR(64),
    update_time      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_credential_app_id UNIQUE (app_id),
    CONSTRAINT chk_app_credential_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

COMMENT ON TABLE ds_app_credential IS '应用凭证表，存储各业务系统的独立认证密钥';
COMMENT ON COLUMN ds_app_credential.app_id IS '业务系统标识（DRONE/SHOP），与业务表 app_id 对应';
COMMENT ON COLUMN ds_app_credential.app_name IS '应用名称（如：无人机系统、商城系统）';
COMMENT ON COLUMN ds_app_credential.app_secret IS '内部 API 密钥（明文存储，令牌认证 + HMAC 签名共用，通过 DB 访问控制保护，原始值仅创建/轮换时返回一次）';
COMMENT ON COLUMN ds_app_credential.mq_secret IS 'MQ 消息签名密钥（明文存储，通过 DB 访问控制保护，原始值仅创建/轮换时返回一次）';
COMMENT ON COLUMN ds_app_credential.allowed_networks IS '允许的 IP/CIDR 网段列表（逗号分隔，如 10.0.0.0/8,172.16.0.0/12），为空时不做 IP 限制（所有 IP 可访问）';
COMMENT ON COLUMN ds_app_credential.status IS '状态: ACTIVE(正常), DISABLED(停用)';
COMMENT ON COLUMN ds_app_credential.description IS '描述信息';
COMMENT ON COLUMN ds_app_credential.created_by IS '创建人';
COMMENT ON COLUMN ds_app_credential.create_time IS '创建时间';
COMMENT ON COLUMN ds_app_credential.updated_by IS '更新人';
COMMENT ON COLUMN ds_app_credential.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_app_credential_status ON ds_app_credential(status);

-- ============================================================
-- 初始数据：预生成的安全随机密钥 (V1_0_11)
-- 下游服务（drone-system 等）需将对应密钥配置到 payment.client.app-secret
-- ============================================================
INSERT INTO ds_app_credential (app_id, app_name, app_secret, mq_secret, allowed_networks, status, created_by)
VALUES ('DRONE', '无人机系统',
        'e036e98d5699bf3a004bf76d7c60155ee052fe16d96be1414d5e39ac946ce9fd',
        '1121190f7ec80b1726be4bf8e560531758828a9fda642fe34543be08314d8066',
        NULL,
        'ACTIVE', 'SYSTEM');

INSERT INTO ds_app_credential (app_id, app_name, app_secret, mq_secret, allowed_networks, status, created_by)
VALUES ('SHOP', '商城系统',
        '942cb571e509a95273f2d08e705fd6de036459fcac8264690193a5b54602b2b5',
        'db1ccd31918c736ce46898a6e0902d7780df39bfa96908a46b617468f7937919',
        NULL,
        'ACTIVE', 'SYSTEM');

-- ============================================================
-- 16. 兑换码表
-- 来源: ExchangeCodeMapper.xml 引用，无对应 Flyway 迁移
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_exchange_code (
    id           BIGSERIAL       PRIMARY KEY,
    code         VARCHAR(64)     NOT NULL,
    coupon_no    VARCHAR(64)     NOT NULL,
    status       VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    used_by      VARCHAR(64),
    used_at      TIMESTAMP,
    expire_at    TIMESTAMP,
    batch_no     VARCHAR(64),
    create_time  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by    VARCHAR(64),
    CONSTRAINT uk_exchange_code UNIQUE (code)
);

COMMENT ON TABLE ds_exchange_code IS '兑换码表，关联优惠券的兑换码';
COMMENT ON COLUMN ds_exchange_code.id IS '主键ID';
COMMENT ON COLUMN ds_exchange_code.code IS '兑换码';
COMMENT ON COLUMN ds_exchange_code.coupon_no IS '关联优惠券编号';
COMMENT ON COLUMN ds_exchange_code.status IS '状态: ACTIVE(可用), USED(已使用), EXPIRED(已过期)';
COMMENT ON COLUMN ds_exchange_code.used_by IS '使用者ID';
COMMENT ON COLUMN ds_exchange_code.used_at IS '使用时间';
COMMENT ON COLUMN ds_exchange_code.expire_at IS '过期时间';
COMMENT ON COLUMN ds_exchange_code.batch_no IS '批次号';
COMMENT ON COLUMN ds_exchange_code.create_time IS '创建时间';
COMMENT ON COLUMN ds_exchange_code.update_time IS '更新时间';
COMMENT ON COLUMN ds_exchange_code.create_by IS '创建者';

CREATE INDEX IF NOT EXISTS idx_exchange_code_batch_no ON ds_exchange_code(batch_no);
CREATE INDEX IF NOT EXISTS idx_exchange_code_status ON ds_exchange_code(status);
