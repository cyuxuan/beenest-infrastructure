-- ============================================================
-- V1_0_10: 新增 app_id 列，实现业务系统级多租户隔离
--
-- 设计说明：
--   biz_type 标识业务类型（DRONE_ORDER/CHANNEL_ORDER/ALLIANCE_MEMBERSHIP/SHOP_ORDER），
--   app_id 标识业务系统（DRONE/SHOP），用于多租户数据隔离。
--   BFF 层按 app_id 过滤，确保业务系统只能查自己的数据；
--   biz_type 由前端透传，用于业务类型筛选。
-- ============================================================

-- 1. ds_payment_order: 添加 app_id
ALTER TABLE ds_payment_order ADD COLUMN IF NOT EXISTS app_id VARCHAR(32);
COMMENT ON COLUMN ds_payment_order.app_id IS '业务系统标识（DRONE/SHOP），用于多租户隔离';

-- 2. ds_wallet: 添加 app_id
ALTER TABLE ds_wallet ADD COLUMN IF NOT EXISTS app_id VARCHAR(32);
COMMENT ON COLUMN ds_wallet.app_id IS '业务系统标识（DRONE/SHOP），用于多租户隔离';

-- 3. ds_wallet_transaction: 添加 app_id
ALTER TABLE ds_wallet_transaction ADD COLUMN IF NOT EXISTS app_id VARCHAR(32);
COMMENT ON COLUMN ds_wallet_transaction.app_id IS '业务系统标识（DRONE/SHOP），用于多租户隔离';

-- 4. ds_withdraw_request: 添加 app_id
ALTER TABLE ds_withdraw_request ADD COLUMN IF NOT EXISTS app_id VARCHAR(32);
COMMENT ON COLUMN ds_withdraw_request.app_id IS '业务系统标识（DRONE/SHOP），用于多租户隔离';

-- 5. ds_service_order: 添加 app_id
ALTER TABLE ds_service_order ADD COLUMN IF NOT EXISTS app_id VARCHAR(32);
COMMENT ON COLUMN ds_service_order.app_id IS '业务系统标识（DRONE/SHOP），用于多租户隔离';

-- ============================================================
-- 数据迁移：从 biz_type 推导 app_id
-- ============================================================

-- ds_payment_order
UPDATE ds_payment_order SET app_id = 'DRONE' WHERE biz_type IN ('DRONE_ORDER', 'CHANNEL_ORDER', 'ALLIANCE_MEMBERSHIP');
UPDATE ds_payment_order SET app_id = 'SHOP' WHERE biz_type = 'SHOP_ORDER';
-- 兜底：biz_type 为空或未匹配的默认归入 DRONE
UPDATE ds_payment_order SET app_id = 'DRONE' WHERE app_id IS NULL;

-- ds_wallet
UPDATE ds_wallet SET app_id = 'DRONE' WHERE biz_type IN ('DRONE_ORDER', 'CHANNEL_ORDER', 'ALLIANCE_MEMBERSHIP');
UPDATE ds_wallet SET app_id = 'SHOP' WHERE biz_type = 'SHOP_ORDER';
UPDATE ds_wallet SET app_id = 'DRONE' WHERE app_id IS NULL;

-- ds_wallet_transaction
UPDATE ds_wallet_transaction SET app_id = 'DRONE' WHERE biz_type IN ('DRONE_ORDER', 'CHANNEL_ORDER', 'ALLIANCE_MEMBERSHIP');
UPDATE ds_wallet_transaction SET app_id = 'SHOP' WHERE biz_type = 'SHOP_ORDER';
UPDATE ds_wallet_transaction SET app_id = 'DRONE' WHERE app_id IS NULL;

-- ds_withdraw_request
UPDATE ds_withdraw_request SET app_id = 'DRONE' WHERE biz_type IN ('DRONE_ORDER', 'CHANNEL_ORDER', 'ALLIANCE_MEMBERSHIP');
UPDATE ds_withdraw_request SET app_id = 'SHOP' WHERE biz_type = 'SHOP_ORDER';
UPDATE ds_withdraw_request SET app_id = 'DRONE' WHERE app_id IS NULL;

-- ds_service_order
UPDATE ds_service_order SET app_id = 'DRONE' WHERE biz_type IN ('DRONE_ORDER', 'CHANNEL_ORDER', 'ALLIANCE_MEMBERSHIP', 'MERCHANT_DEPOSIT');
UPDATE ds_service_order SET app_id = 'SHOP' WHERE biz_type = 'SHOP_ORDER';
UPDATE ds_service_order SET app_id = 'DRONE' WHERE app_id IS NULL;

-- ============================================================
-- 设置 NOT NULL 约束 + 默认值 + 索引
-- ============================================================

ALTER TABLE ds_payment_order ALTER COLUMN app_id SET NOT NULL;
ALTER TABLE ds_payment_order ALTER COLUMN app_id SET DEFAULT 'DRONE';
CREATE INDEX IF NOT EXISTS idx_payment_order_app_id ON ds_payment_order(app_id);

ALTER TABLE ds_wallet ALTER COLUMN app_id SET NOT NULL;
ALTER TABLE ds_wallet ALTER COLUMN app_id SET DEFAULT 'DRONE';
CREATE INDEX IF NOT EXISTS idx_wallet_app_id ON ds_wallet(app_id);

ALTER TABLE ds_wallet_transaction ALTER COLUMN app_id SET NOT NULL;
ALTER TABLE ds_wallet_transaction ALTER COLUMN app_id SET DEFAULT 'DRONE';
CREATE INDEX IF NOT EXISTS idx_wallet_transaction_app_id ON ds_wallet_transaction(app_id);

ALTER TABLE ds_withdraw_request ALTER COLUMN app_id SET NOT NULL;
ALTER TABLE ds_withdraw_request ALTER COLUMN app_id SET DEFAULT 'DRONE';
CREATE INDEX IF NOT EXISTS idx_withdraw_request_app_id ON ds_withdraw_request(app_id);

ALTER TABLE ds_service_order ALTER COLUMN app_id SET NOT NULL;
ALTER TABLE ds_service_order ALTER COLUMN app_id SET DEFAULT 'DRONE';
CREATE INDEX IF NOT EXISTS idx_service_order_app_id ON ds_service_order(app_id);
