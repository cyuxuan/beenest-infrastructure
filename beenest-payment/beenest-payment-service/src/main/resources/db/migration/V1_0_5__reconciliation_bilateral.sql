-- =====================================================
-- 对账模块第二阶段：双边对账扩展字段
-- 在 ds_payment_reconciliation_task 表新增平台侧数据字段
-- =====================================================

-- 平台账单笔数
ALTER TABLE ds_payment_reconciliation_task ADD COLUMN IF NOT EXISTS platform_order_count INT;
COMMENT ON COLUMN ds_payment_reconciliation_task.platform_order_count IS '平台账单笔数（第三方返回的交易记录数）';

-- 平台账单金额（分）
ALTER TABLE ds_payment_reconciliation_task ADD COLUMN IF NOT EXISTS platform_amount BIGINT;
COMMENT ON COLUMN ds_payment_reconciliation_task.platform_amount IS '平台账单金额（分，第三方返回的交易总额）';

-- 不匹配明细（JSON格式）
ALTER TABLE ds_payment_reconciliation_task ADD COLUMN IF NOT EXISTS detail TEXT;
COMMENT ON COLUMN ds_payment_reconciliation_task.detail IS '不匹配明细（JSON数组，包含AMOUNT_MISMATCH/STATUS_MISMATCH/LOCAL_ONLY/PLATFORM_ONLY等类型）';
