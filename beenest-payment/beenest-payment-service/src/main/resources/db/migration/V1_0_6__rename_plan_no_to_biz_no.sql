-- 支付订单 plan_no 泛化为 biz_no，解耦无人机业务语义
ALTER TABLE ds_payment_order RENAME COLUMN plan_no TO biz_no;

COMMENT ON COLUMN ds_payment_order.biz_no IS '关联业务单号（通用化，由调用方传入）';
