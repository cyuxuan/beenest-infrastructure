-- =====================================================
-- MQ 消息 Outbox 表
-- 用于保证 MQ 消息发送的最终一致性
-- 先写入 outbox 表，发送成功后标记为 SENT
-- 发送失败后由定时任务补偿重发
-- =====================================================

CREATE TABLE IF NOT EXISTS ds_payment_outbox (
    id              BIGSERIAL PRIMARY KEY,
    message_id      VARCHAR(64) NOT NULL,
    exchange        VARCHAR(128),
    routing_key     VARCHAR(128) NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count     INT NOT NULL DEFAULT 0,
    max_retry       INT NOT NULL DEFAULT 5,
    next_retry_time TIMESTAMP,
    error_message   TEXT,
    create_time     TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time     TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_outbox_message_id UNIQUE (message_id),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_next_retry ON ds_payment_outbox(status, next_retry_time);

COMMENT ON TABLE ds_payment_outbox IS 'MQ消息Outbox表，保证消息发送最终一致性';
COMMENT ON COLUMN ds_payment_outbox.status IS 'PENDING-待发送, SENT-已发送, FAILED-发送失败';
COMMENT ON COLUMN ds_payment_outbox.next_retry_time IS '下次重试时间，指数退避';
