-- 回滚 V1.3.0:移除 offline_access 是单向清理,原值无法还原。
-- 若需恢复某个客户端的作用域,由管理员在控制台重新登记(登记接口会再次拒绝 offline_access)。
-- 本脚本只把列注释改回迁移前的措辞,保证结构描述一致。

COMMENT ON COLUMN iam_client.scopes IS '客户端可申请的作用域,空格分隔';
