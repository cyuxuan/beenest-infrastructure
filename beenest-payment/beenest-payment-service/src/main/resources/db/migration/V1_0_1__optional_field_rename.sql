-- =====================================================
-- Phase 7: 支付中台独立化 - 数据字段重命名（可选执行）
--
-- 警告：此脚本包含破坏性变更（字段重命名），请在确认无影响后执行
-- 建议先在测试环境验证
-- =====================================================

-- 以下为可选的字段重命名，将 customer_no → user_no, plan_no → biz_no
-- 实际是否执行取决于业务系统是否已适配

-- 方案1: 添加新字段并保留旧字段（向后兼容，推荐）
-- ALTER TABLE ds_payment_order ADD COLUMN IF NOT EXISTS user_no VARCHAR(64);
-- ALTER TABLE ds_payment_order ADD COLUMN IF NOT EXISTS biz_no VARCHAR(64);
-- UPDATE ds_payment_order SET user_no = customer_no WHERE user_no IS NULL;
-- UPDATE ds_payment_order SET biz_no = plan_no WHERE biz_no IS NULL;

-- 方案2: 直接重命名（需要停机维护）
-- ALTER TABLE ds_payment_order RENAME COLUMN customer_no TO user_no;
-- ALTER TABLE ds_payment_order RENAME COLUMN plan_no TO biz_no;

-- ALTER TABLE ds_wallet RENAME COLUMN customer_no TO user_no;
-- ALTER TABLE ds_wallet_transaction RENAME COLUMN customer_no TO user_no;

-- ALTER TABLE ds_user_coupon RENAME COLUMN customer_no TO user_no;

-- ALTER TABLE ds_withdraw_request RENAME COLUMN customer_no TO user_no;
