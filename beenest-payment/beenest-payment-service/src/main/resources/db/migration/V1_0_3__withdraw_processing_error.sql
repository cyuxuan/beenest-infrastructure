-- =====================================================
-- 提现处理异常状态支持
-- 新增 PROCESSING_ERROR 状态，用于标记第三方提现成功但本地处理异常的场景
-- 此时资金已在第三方扣除，不能自动解冻，需人工介入确认
-- =====================================================

-- 添加 CHECK 约束确保状态值合法
ALTER TABLE ds_withdraw_request DROP CONSTRAINT IF EXISTS chk_withdraw_status;
ALTER TABLE ds_withdraw_request ADD CONSTRAINT chk_withdraw_status
    CHECK (status IN ('PENDING', 'MANUAL_REVIEW', 'APPROVED', 'PROCESSING', 'SUCCESS', 'FAILED', 'CANCELLED', 'REJECTED', 'PROCESSING_ERROR'));

COMMENT ON CONSTRAINT chk_withdraw_status ON ds_withdraw_request IS '提现状态合法值约束，含PROCESSING_ERROR异常待人工处理状态';

-- =====================================================
-- 支付订单号唯一约束（防并发重复订单号）
-- =====================================================
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_order_no ON ds_payment_order(order_no);
