-- =====================================================
-- 幂等性安全增强：为关键字段添加唯一约束
-- =====================================================

-- 1. 交易流水 reference_no 唯一索引（部分索引，仅非空行生效）
-- 防止并发场景下同一 referenceNo 重复入账导致资金双倍到账
CREATE UNIQUE INDEX IF NOT EXISTS uk_wallet_transaction_reference_no
    ON ds_wallet_transaction (reference_no)
    WHERE reference_no IS NOT NULL AND reference_no != '';

-- 2. 支付订单 order_no 已有 UNIQUE 约束（确认存在性，不重复创建）
-- ds_payment_order.order_no 在建表时已有 UNIQUE 约束

-- 3. 退款单 refund_no 已有 UNIQUE 约束（确认存在性，不重复创建）
-- ds_refund.refund_no 在建表时已有 UNIQUE 约束

COMMENT ON INDEX uk_wallet_transaction_reference_no IS '幂等保护：防止同一关联单号重复入账';
