-- =====================================================
-- Phase 7: 支付中台独立化 - 数据库迁移脚本
-- 将 payment 相关表从 drone 数据库迁移到 payment 独立数据库
--
-- 说明：以下 ALTER 语句用于将 customer_no 改为通用字段
-- 注意：实际执行时需要根据是否新建独立数据库来决定
--   方案A：payment 使用独立数据库 → 需要先 CREATE DATABASE，再迁移表
--   方案B：payment 与 drone 共用数据库 → 只需 ALTER 字段
-- =====================================================

-- 1. PaymentOrder 表改造
-- plan_no → biz_no（业务单号，通用化）
-- 新增 biz_type（业务类型）
-- 新增 ext（扩展字段，JSON格式）
ALTER TABLE ds_payment_order ADD COLUMN IF NOT EXISTS biz_type VARCHAR(32) DEFAULT 'DRONE_ORDER';
ALTER TABLE ds_payment_order ADD COLUMN IF NOT EXISTS ext TEXT;

-- 添加注释说明字段含义变更
COMMENT ON COLUMN ds_payment_order.plan_no IS '关联业务单号（通用化，原plan_no）';
COMMENT ON COLUMN ds_payment_order.biz_type IS '业务类型标识（DRONE_ORDER, SHOP_ORDER等）';

-- 2. Wallet 表 - 无需修改字段名，customer_no 在支付中台语义通用
-- 但添加注释说明通用化
COMMENT ON COLUMN ds_wallet.customer_no IS '用户编号（通用化，不限于客户）';

-- 3. WalletTransaction 表
COMMENT ON COLUMN ds_wallet_transaction.customer_no IS '用户编号（通用化）';

-- 4. 添加索引优化
CREATE INDEX IF NOT EXISTS idx_payment_order_biz_no ON ds_payment_order(plan_no);
CREATE INDEX IF NOT EXISTS idx_payment_order_biz_type ON ds_payment_order(biz_type);

-- 5. 退款表添加通知 URL 字段（如果不存在）
-- ALTER TABLE ds_refund ADD COLUMN IF NOT EXISTS notify_url VARCHAR(512);
