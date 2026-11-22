-- ============================================================
-- beenest-iam 主体标识策略迁移(V1.0.8)
-- 1. iam_realm.subject_mode:域级默认策略(LOGIN_NAME / USER_ID)
-- 2. iam_client.subject_mode:客户端覆盖(NULL 表示继承域策略)
-- 说明:OIDC 的 sub 此前恒等于登录名。登录名是可变业务字段
--       (改名、账号迁移、跨系统合并),一旦变更,应用侧按 sub 建立的
--       用户映射集体断裂,且无法回溯到同一个自然人。
--       本次把 sub 的来源做成可配置:
--         LOGIN_NAME 兼容存量接入(默认,行为不变);
--         USER_ID    用不可变的用户主键,登录名怎么改都不影响应用。
--       两级设计的原因:切换是逐应用进行的破坏性变更,
--       存量应用保留登录名语义、新接入应用直接用 ID,互不牵连。
-- ============================================================

-- 1. 域级默认策略:NOT NULL + 默认值,存量域升级后行为与升级前完全一致
ALTER TABLE iam_realm ADD COLUMN IF NOT EXISTS subject_mode VARCHAR(20) NOT NULL DEFAULT 'LOGIN_NAME';
COMMENT ON COLUMN iam_realm.subject_mode IS
        'OIDC sub 的取值策略:LOGIN_NAME 登录名(兼容存量)/ USER_ID 不可变用户主键';

-- 2. 客户端级覆盖:NULL = 继承所属域
ALTER TABLE iam_client ADD COLUMN IF NOT EXISTS subject_mode VARCHAR(20);
COMMENT ON COLUMN iam_client.subject_mode IS
        'OIDC sub 的取值策略;为空表示继承所属身份域的 subject_mode';

-- 3. 存量行的兜底:建表语句已带默认值,这里只为"先建列后补默认"的历史库兜底
UPDATE iam_realm SET subject_mode = 'LOGIN_NAME' WHERE subject_mode IS NULL OR subject_mode = '';
