-- CAS用户表增加 tenant_id（存储注册来源的appid，底座字段）
ALTER TABLE cas_user ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT '';
COMMENT ON COLUMN cas_user.tenant_id IS '租户ID，存储注册来源的appid，底座字段';

-- 索引（后续多租户查询需要，当前仅写入不影响性能）
CREATE INDEX IF NOT EXISTS idx_cas_user_tenant_id ON cas_user(tenant_id);
