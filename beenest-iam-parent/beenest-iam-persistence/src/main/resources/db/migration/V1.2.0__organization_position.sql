-- ============================================================
-- 本迁移:组织架构与岗位(P1-1)
--   1. iam_group 类型化:group_type 区分「部门」与「普通组」,并补负责人、
--      排序与外部主键(HR 同步锚点)。部门复用组已有的树形与组角色能力,
--      不另起一套组织表——两套树最终一定会不一致
--   2. iam_user_group 任职化:position_id 表达"在该部门担任什么岗位",
--      is_primary 表达主部门(一人只可能有一个主部门,其余为兼职)
--   3. iam_position:域内岗位字典(岗位 ≠ 角色:岗位是人事语义,角色是授权语义)
--   4. iam_position_role:岗位默认角色,与组角色同样并入权限公式
-- 语义要点:
--   - 层级仍然只表达归属,子部门成员不继承父部门角色(继承会让"撤权限"做不到)
--   - 岗位角色按"任职"生效:同一个人换部门换岗位,权限随之变化
-- ============================================================

-- ---------- 1. 组类型化 ----------

ALTER TABLE iam_group ADD COLUMN IF NOT EXISTS group_type VARCHAR(20) NOT NULL DEFAULT 'GROUP';
ALTER TABLE iam_group ADD COLUMN IF NOT EXISTS leader_user_id BIGINT;
ALTER TABLE iam_group ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;
ALTER TABLE iam_group ADD COLUMN IF NOT EXISTS external_id VARCHAR(128);

COMMENT ON COLUMN iam_group.group_type IS 'DEPARTMENT=部门(组织架构节点) / GROUP=普通用户组;仅影响展示与筛选,不参与权限继承';
COMMENT ON COLUMN iam_group.leader_user_id IS '部门负责人(用户主键);仅用于展示与流程指派,不自带任何权限';
COMMENT ON COLUMN iam_group.external_id IS '外部系统(HR/AD)的组织主键,用于同步对齐;不参与任何鉴权';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_iam_group_type') THEN
        ALTER TABLE iam_group
            ADD CONSTRAINT ck_iam_group_type CHECK (group_type IN ('DEPARTMENT', 'GROUP'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_iam_group_type
    ON iam_group(realm_id, group_type) WHERE deleted = FALSE;

-- ---------- 2. 任职化:用户-组关系带岗位与主部门标记 ----------

ALTER TABLE iam_user_group ADD COLUMN IF NOT EXISTS position_id BIGINT;
ALTER TABLE iam_user_group ADD COLUMN IF NOT EXISTS is_primary BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN iam_user_group.position_id IS '该成员在本部门担任的岗位;岗位默认角色经此进入权限计算';
COMMENT ON COLUMN iam_user_group.is_primary IS '是否主部门;一人至多一个主部门,其余为兼职';

-- 主部门唯一:索引而非应用判断——并发下两条 INSERT 都能通过各自的查重
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_user_group_primary
    ON iam_user_group(user_id) WHERE deleted = FALSE AND is_primary;

CREATE INDEX IF NOT EXISTS idx_iam_user_group_position
    ON iam_user_group(position_id) WHERE deleted = FALSE;

-- ---------- 3. 岗位字典 ----------

CREATE TABLE IF NOT EXISTS iam_position (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    position_code VARCHAR(100) NOT NULL,
    position_name VARCHAR(100) NOT NULL,
    -- 职级/序列(自由文本):国内 HR 里岗位与职级常被并列展示,如 P7 / M2 / 正科级
    position_level VARCHAR(32) NOT NULL DEFAULT '',
    sort_order INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE iam_position IS '域内岗位字典(人事语义);岗位经 iam_position_role 携带默认角色参与授权';
COMMENT ON COLUMN iam_position.position_level IS '职级/序列展示用文本,不参与鉴权';

CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_position_code
    ON iam_position(realm_id, position_code) WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_iam_position_realm
    ON iam_position(realm_id) WHERE deleted = FALSE;

-- ---------- 4. 岗位默认角色 ----------

CREATE TABLE IF NOT EXISTS iam_position_role (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    position_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    granted_by BIGINT,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE iam_position_role IS '岗位默认角色;用户任职该岗位即继承这些角色,与组角色等价并入权限公式';

CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_position_role
    ON iam_position_role(position_id, role_id) WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_iam_position_role_role
    ON iam_position_role(role_id) WHERE deleted = FALSE;

-- ---------- 5. 补种岗位管理权限码 ----------

INSERT INTO iam_permission(realm_id, permission_code, permission_name, resource, action)
SELECT r.id, v.code, v.name, v.resource, v.action
FROM iam_realm r
CROSS JOIN (
    VALUES
        ('position:view', '查看岗位', 'position', 'view'),
        ('position:create', '新建岗位', 'position', 'create'),
        ('position:update', '修改岗位', 'position', 'update'),
        ('position:delete', '删除岗位', 'position', 'delete'),
        ('position:assign-role', '分配岗位角色', 'position', 'assign-role')
) AS v(code, name, resource, action)
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_permission p WHERE p.realm_id = r.id AND p.permission_code = v.code AND NOT p.deleted);

INSERT INTO iam_role_permission(realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE r.realm_key = 'default'
  AND p.permission_code IN ('position:view', 'position:create', 'position:update',
                            'position:delete', 'position:assign-role')
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);
