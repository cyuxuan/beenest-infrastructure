-- 移除 offline_access:平台不实现离线令牌语义(刷新令牌随会话结束失效),
-- 登记了却不实现会让接入方误以为拿到长期令牌。
-- 幂等:无匹配行时不更新任何记录。

UPDATE iam_client
SET scopes = btrim(regexp_replace(
        regexp_replace(scopes, '\moffline_access\M', '', 'g'),
        '\s{2,}', ' ', 'g')),
    updated_time = CURRENT_TIMESTAMP
WHERE deleted = FALSE
  AND scopes ~ '\moffline_access\M';

UPDATE iam_consent
SET scopes = btrim(regexp_replace(
        regexp_replace(scopes, '\moffline_access\M', '', 'g'),
        '\s{2,}', ' ', 'g')),
    updated_time = CURRENT_TIMESTAMP
WHERE deleted = FALSE
  AND scopes ~ '\moffline_access\M';

COMMENT ON COLUMN iam_client.scopes IS '客户端可申请的作用域,空格分隔;不含 offline_access(平台不提供离线令牌语义)';
