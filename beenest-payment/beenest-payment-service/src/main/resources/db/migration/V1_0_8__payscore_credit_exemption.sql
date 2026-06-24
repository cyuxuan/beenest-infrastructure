-- =====================================================
-- 支付分（信用免押）数据表
-- 业务场景：商户入驻免押金，通过微信支付分/支付宝芝麻信用替代保证金
-- =====================================================

-- 服务订单表（信用免押）
CREATE TABLE ds_service_order (
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

-- 信用授权记录表
CREATE TABLE ds_credit_authorization (
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

-- 索引
CREATE INDEX idx_service_order_customer ON ds_service_order(customer_no);
CREATE INDEX idx_service_order_biz_no ON ds_service_order(biz_no);
CREATE INDEX idx_service_order_status ON ds_service_order(status);
CREATE INDEX idx_service_order_platform ON ds_service_order(platform);
CREATE INDEX idx_credit_auth_order ON ds_credit_authorization(order_no);
CREATE INDEX idx_credit_auth_customer ON ds_credit_authorization(customer_no);
