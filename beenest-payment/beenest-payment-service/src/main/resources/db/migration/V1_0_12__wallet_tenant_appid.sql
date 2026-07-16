-- 钱包多租户隔离维度变更：从 biz_type 改为 app_id
-- 同一用户每个 appId 只有一个钱包，bizType 降级为业务标记字段

-- 1. 删除旧唯一约束（customer_no, biz_type）
DROP INDEX IF EXISTS uk_wallet_customer_biz;

-- 2. 创建新唯一约束（customer_no, app_id）
CREATE UNIQUE INDEX IF NOT EXISTS uk_wallet_customer_appid ON ds_wallet(customer_no, app_id);

-- 3. 为已有数据补全 app_id（按 biz_type 推导）
UPDATE ds_wallet SET app_id = 'DRONE' WHERE app_id IS NULL AND biz_type IN ('DRONE_ORDER', 'CHANNEL_ORDER', 'ALLIANCE_MEMBERSHIP');
UPDATE ds_wallet SET app_id = 'SHOP' WHERE app_id IS NULL AND biz_type = 'SHOP_ORDER';
UPDATE ds_wallet SET app_id = 'DRONE' WHERE app_id IS NULL;

-- 4. 为已有交易记录补全 app_id
UPDATE ds_wallet_transaction SET app_id = 'DRONE' WHERE app_id IS NULL AND biz_type IN ('DRONE_ORDER', 'CHANNEL_ORDER', 'ALLIANCE_MEMBERSHIP');
UPDATE ds_wallet_transaction SET app_id = 'SHOP' WHERE app_id IS NULL AND biz_type = 'SHOP_ORDER';
UPDATE ds_wallet_transaction SET app_id = 'DRONE' WHERE app_id IS NULL;
