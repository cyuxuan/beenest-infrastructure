-- =====================================================
-- CAS 访问控制集成：钱包表增加用户画像字段
-- PaymentAccessControlService 通过 CAS 属性同步用户姓名和手机号
-- =====================================================

-- 1. 钱包表增加客户姓名和手机号字段
ALTER TABLE ds_wallet ADD COLUMN IF NOT EXISTS customer_name VARCHAR(128);
ALTER TABLE ds_wallet ADD COLUMN IF NOT EXISTS customer_phone VARCHAR(32);

-- 2. 添加字段注释
COMMENT ON COLUMN ds_wallet.customer_name IS '客户姓名（从 CAS 属性同步）';
COMMENT ON COLUMN ds_wallet.customer_phone IS '客户手机号（从 CAS 属性同步）';

-- 3. 添加索引（按手机号查询用户钱包）
CREATE INDEX IF NOT EXISTS idx_wallet_customer_phone ON ds_wallet(customer_phone);
