-- 回滚 V1.1.4:移除门户自发起标记。
-- 该列只承载"门户是否代发授权地址"的开关,删除后门户回到统一代发授权的旧行为;
-- 对自带 SSO 发起端点的应用,门户代发的授权地址缺少 code_challenge,
-- 公共客户端会被授权端点以 invalid_request 拒绝——这是回滚后的已知行为退化。

ALTER TABLE iam_application_service DROP COLUMN IF EXISTS sso_self_initiated;
