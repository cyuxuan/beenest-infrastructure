-- =====================================================
-- V1_0_13: 多租户隔离维度清理 — 删除冗余 biz_type，补全 app_id
--
-- 设计说明：
--   多租户隔离统一使用 app_id（由 TenantAppIdInterceptor 自动注入）。
--   biz_type 仅在需要区分同一 appId 下不同业务类型的表中保留
--   （如 ds_payment_order 区分 DRONE_ORDER/CHANNEL_ORDER，
--   ds_service_order 区分 MERCHANT_DEPOSIT）。
--
--   对于只需要多租户隔离、不需要业务类型细分的表，biz_type 是冗余的，
--   应删除以避免混淆和误用。
--
-- 变更清单：
--   1. ds_wallet: 删除 biz_type 列及相关索引
--   2. ds_wallet_transaction: 删除 biz_type 列及相关索引
--   3. ds_withdraw_request: 删除 biz_type 列及相关索引
--   4. ds_credit_authorization: 新增 app_id 列（该表在拦截器白名单但缺此列）
-- =====================================================

-- ==================== 1. ds_wallet: 删除 biz_type ====================

-- 删除旧索引（V1_0_2 创建的 biz_type 索引）
DROP INDEX IF EXISTS idx_wallet_biz_type;

-- 删除 biz_type 列
ALTER TABLE ds_wallet DROP COLUMN IF EXISTS biz_type;

-- 确认新唯一约束存在（V1_0_12 已创建，此处做幂等保障）
CREATE UNIQUE INDEX IF NOT EXISTS uk_wallet_customer_appid ON ds_wallet(customer_no, app_id);


-- ==================== 2. ds_wallet_transaction: 删除 biz_type ====================

-- 删除旧索引
DROP INDEX IF EXISTS idx_wallet_transaction_biz_type;

-- 删除 biz_type 列
ALTER TABLE ds_wallet_transaction DROP COLUMN IF EXISTS biz_type;


-- ==================== 3. ds_withdraw_request: 删除 biz_type ====================

-- 删除旧索引
DROP INDEX IF EXISTS idx_withdraw_request_biz_type;

-- 删除 biz_type 列
ALTER TABLE ds_withdraw_request DROP COLUMN IF EXISTS biz_type;


-- ==================== 4. ds_credit_authorization: 新增 app_id ====================

-- 新增 app_id 列
ALTER TABLE ds_credit_authorization ADD COLUMN IF NOT EXISTS app_id VARCHAR(32);
COMMENT ON COLUMN ds_credit_authorization.app_id IS '业务系统标识（DRONE/SHOP），用于多租户隔离';

-- 数据迁移：通过关联 ds_service_order 推导 app_id
UPDATE ds_credit_authorization ca
SET app_id = (
    SELECT so.app_id
    FROM ds_service_order so
    WHERE so.order_no = ca.order_no
)
WHERE ca.app_id IS NULL;

-- 兜底：未关联到 service_order 的默认归入 DRONE
UPDATE ds_credit_authorization SET app_id = 'DRONE' WHERE app_id IS NULL;

-- 设置 NOT NULL 约束 + 默认值 + 索引
ALTER TABLE ds_credit_authorization ALTER COLUMN app_id SET NOT NULL;
ALTER TABLE ds_credit_authorization ALTER COLUMN app_id SET DEFAULT 'DRONE';
CREATE INDEX IF NOT EXISTS idx_credit_authorization_app_id ON ds_credit_authorization(app_id);
