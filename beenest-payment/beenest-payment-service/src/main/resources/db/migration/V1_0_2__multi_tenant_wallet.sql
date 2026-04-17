-- =====================================================
-- V1_0_2: 多租户钱包隔离
-- 同一用户在不同业务系统下拥有独立钱包，余额互不共享
-- =====================================================

-- 1. ds_wallet: 添加 biz_type
ALTER TABLE ds_wallet ADD COLUMN IF NOT EXISTS biz_type VARCHAR(32) DEFAULT 'DRONE_ORDER';
COMMENT ON COLUMN ds_wallet.biz_type IS '业务类型标识（DRONE_ORDER, SHOP_ORDER等），实现多租户钱包隔离';

-- 唯一索引：同一用户同一业务类型只能有一个钱包
CREATE UNIQUE INDEX IF NOT EXISTS uk_wallet_customer_biz ON ds_wallet(customer_no, biz_type);
CREATE INDEX IF NOT EXISTS idx_wallet_biz_type ON ds_wallet(biz_type);

-- 2. ds_wallet_transaction: 添加 biz_type
ALTER TABLE ds_wallet_transaction ADD COLUMN IF NOT EXISTS biz_type VARCHAR(32) DEFAULT 'DRONE_ORDER';
COMMENT ON COLUMN ds_wallet_transaction.biz_type IS '业务类型标识，用于多租户隔离';
CREATE INDEX IF NOT EXISTS idx_wallet_transaction_biz_type ON ds_wallet_transaction(biz_type);

-- 3. ds_withdraw_request: 添加 biz_type
ALTER TABLE ds_withdraw_request ADD COLUMN IF NOT EXISTS biz_type VARCHAR(32) DEFAULT 'DRONE_ORDER';
COMMENT ON COLUMN ds_withdraw_request.biz_type IS '业务类型标识，用于多租户隔离';
CREATE INDEX IF NOT EXISTS idx_withdraw_request_biz_type ON ds_withdraw_request(biz_type);
