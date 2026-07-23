-- ============================================================
-- drone_system schema 初始化
-- 云Go无人机管理系统 — 完整建表脚本
-- ============================================================
-- 合并了所有 Flyway 迁移的最终状态：
--   V2.0.0: V1~V44, V52
--   V2.1.0: V1~V9 (feature/ddl + feature/dml)
-- Flyway 已移除，此脚本为唯一初始化来源。
-- PostgreSQL initdb 首次创建 beenest 数据库时自动执行。
-- ============================================================

-- 设置 search_path 到 drone_system schema，所有后续对象默认创建在此 schema 下
SET search_path TO drone_system;

-- 启用 PostGIS 扩展（V33）
-- 注意：标准 postgres:16-alpine 镜像不包含 postgis，
-- 此处忽略错误，使用 postgis/postgis 镜像时自动生效
DO $$ BEGIN
    CREATE EXTENSION IF NOT EXISTS postgis;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'PostGIS extension not available, skipping. Install postgis/postgis image for spatial features.';
END $$;

-- ============================================================
-- 用户/账户表
-- ============================================================

-- 系统账号表（统一账号）
CREATE TABLE IF NOT EXISTS ds_sys_user (
    id             BIGSERIAL       PRIMARY KEY,
    sys_user_id    VARCHAR(64)     NOT NULL,
    account        VARCHAR(64)     NOT NULL,
    username       VARCHAR(64)     NOT NULL,
    phone          CHAR(11),
    email          VARCHAR(128),
    password       VARCHAR(64)     NOT NULL,
    account_status SMALLINT        DEFAULT 1,
    create_time    TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time    TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE ds_sys_user IS '系统账号表（统一账号）';
COMMENT ON COLUMN ds_sys_user.id IS '系统账号唯一标识（自增主键）';
COMMENT ON COLUMN ds_sys_user.sys_user_id IS '系统账号唯一ID';
COMMENT ON COLUMN ds_sys_user.account IS '系统账号（唯一）';
COMMENT ON COLUMN ds_sys_user.username IS '登录用户名（唯一）';
COMMENT ON COLUMN ds_sys_user.phone IS '手机号（可选，唯一）';
COMMENT ON COLUMN ds_sys_user.email IS '邮箱（可选，唯一）';
COMMENT ON COLUMN ds_sys_user.password IS '登录密码（BCrypt加密存储）';
COMMENT ON COLUMN ds_sys_user.account_status IS '账号状态（0-封禁，1-正常）';
COMMENT ON COLUMN ds_sys_user.create_time IS '账号创建时间';
COMMENT ON COLUMN ds_sys_user.update_time IS '信息最后更新时间（自动维护）';

CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_sys_user_account ON ds_sys_user(account);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_sys_user_username ON ds_sys_user(username);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_sys_user_phone ON ds_sys_user(phone);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_sys_user_email ON ds_sys_user(email);

-- 用户详情表（普通用户）
CREATE TABLE IF NOT EXISTS ds_user (
    id          BIGSERIAL       PRIMARY KEY,
    user_id     VARCHAR(64)     NOT NULL,
    sys_user_id VARCHAR(64)     NOT NULL,
    nickname    VARCHAR(64)     NOT NULL,
    avatar      VARCHAR(255)    DEFAULT '',
    gender      SMALLINT        DEFAULT 0,
    id_card     CHAR(64),
    real_name   VARCHAR(32),
    auth_status SMALLINT        DEFAULT 0,
    user_status SMALLINT        DEFAULT 1,
    create_time TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE ds_user IS '用户详情表（普通用户）';
COMMENT ON COLUMN ds_user.id IS '用户唯一标识（自增主键）';
COMMENT ON COLUMN ds_user.user_id IS '用户唯一ID';
COMMENT ON COLUMN ds_user.sys_user_id IS '关联系统账号ID（外键关联sys_user表）';
COMMENT ON COLUMN ds_user.nickname IS '用户昵称（显示用）';
COMMENT ON COLUMN ds_user.avatar IS '头像图片URL（存储于对象存储）';
COMMENT ON COLUMN ds_user.gender IS '性别（0-未知，1-男，2-女）';
COMMENT ON COLUMN ds_user.id_card IS '身份证号（脱敏存储）';
COMMENT ON COLUMN ds_user.real_name IS '真实姓名（实名认证后填充）';
COMMENT ON COLUMN ds_user.auth_status IS '实名认证状态（0-未认证，1-审核中，2-已认证，3-已拒绝）';
COMMENT ON COLUMN ds_user.user_status IS '用户账号状态（0-封禁，1-正常）';
COMMENT ON COLUMN ds_user.create_time IS '账号创建时间';
COMMENT ON COLUMN ds_user.update_time IS '信息最后更新时间（自动维护）';

CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_user_user_id ON ds_user(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_user_sys_user_id ON ds_user(sys_user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_user_id_card ON ds_user(id_card);

-- 客户表（小程序用户）
CREATE TABLE IF NOT EXISTS ds_customer (
    id           BIGSERIAL       PRIMARY KEY,
    customer_no  VARCHAR(64)     NOT NULL,
    phone_number VARCHAR(20),
    openid       VARCHAR(64),
    source       VARCHAR(20),
    nickname     VARCHAR(64),
    avatar_url   TEXT,
    create_time  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ds_customer_no UNIQUE (customer_no)
);

COMMENT ON TABLE ds_customer IS '客户表';
COMMENT ON COLUMN ds_customer.id IS '主键ID';
COMMENT ON COLUMN ds_customer.customer_no IS '客户业务编号';
COMMENT ON COLUMN ds_customer.phone_number IS '手机号';
COMMENT ON COLUMN ds_customer.openid IS '第三方OpenID';
COMMENT ON COLUMN ds_customer.source IS '来源';
COMMENT ON COLUMN ds_customer.nickname IS '昵称';
COMMENT ON COLUMN ds_customer.avatar_url IS '头像数据，支持URL或Base64编码格式';
COMMENT ON COLUMN ds_customer.create_time IS '创建时间';
COMMENT ON COLUMN ds_customer.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_customer_phone ON ds_customer(phone_number);
CREATE INDEX IF NOT EXISTS idx_customer_openid_source ON ds_customer(openid, source);

-- 统一登录用户（本地副本，权威源在 beenest SSO）
CREATE TABLE IF NOT EXISTS ds_unified_user (
    id                      BIGSERIAL       PRIMARY KEY,
    user_id                 VARCHAR(64)     NOT NULL,
    user_type               VARCHAR(32)     DEFAULT 'CUSTOMER',
    identity                VARCHAR(32)     DEFAULT 'CUSTOMER',
    source                  VARCHAR(32)     DEFAULT 'MINIAPP',
    login_type              VARCHAR(32)     DEFAULT 'WECHAT',
    openid                  VARCHAR(128),
    unionid                 VARCHAR(128),
    username                VARCHAR(64),
    phone                   VARCHAR(20),
    email                   VARCHAR(128),
    nickname                VARCHAR(100),
    avatar_url              VARCHAR(512),
    password_hash           VARCHAR(255),
    password_algo           VARCHAR(32)     DEFAULT 'BCRYPT',
    password_salt           VARCHAR(64),
    password_update_time    TIMESTAMP,
    mfa_enabled             BOOLEAN         DEFAULT FALSE,
    mfa_secret_encrypted    VARCHAR(255),
    mfa_bind_time           TIMESTAMP,
    phone_verified          BOOLEAN         DEFAULT FALSE,
    phone_verified_time     TIMESTAMP,
    email_verified          BOOLEAN         DEFAULT FALSE,
    email_verified_time     TIMESTAMP,
    real_name_verified      BOOLEAN         DEFAULT FALSE,
    real_name_verified_time TIMESTAMP,
    status                  SMALLINT        DEFAULT 0,
    disabled_reason         VARCHAR(255),
    disabled_time           TIMESTAMP,
    failed_login_count      INTEGER         DEFAULT 0,
    last_failed_login_time  TIMESTAMP,
    lock_until_time         TIMESTAMP,
    last_login_time         TIMESTAMP,
    last_login_ip           VARCHAR(64),
    last_login_user_agent   VARCHAR(512),
    last_login_device_id    VARCHAR(128),
    register_time           TIMESTAMP,
    register_ip             VARCHAR(64),
    token_version           INTEGER         DEFAULT 1,
    create_time             TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_unified_user_user_id UNIQUE (user_id),
    CONSTRAINT uk_unified_user_username UNIQUE (username)
);

COMMENT ON TABLE ds_unified_user IS '统一登录用户（本地副本）';
COMMENT ON COLUMN ds_unified_user.id IS '主键';
COMMENT ON COLUMN ds_unified_user.user_id IS '用户业务编号/全局用户ID';
COMMENT ON COLUMN ds_unified_user.user_type IS '用户类型：CUSTOMER/PROVIDER/ADMIN/STAFF';
COMMENT ON COLUMN ds_unified_user.identity IS '用户身份：CUSTOMER/PILOT/OPERATOR/SUPER_ADMIN';
COMMENT ON COLUMN ds_unified_user.source IS '账号来源：MINIAPP/SYSTEM/WEB/APP';
COMMENT ON COLUMN ds_unified_user.login_type IS '登录类型：WECHAT/DOUYIN/USERNAME_PASSWORD/PHONE_SMS/SSO';
COMMENT ON COLUMN ds_unified_user.openid IS '第三方 OpenID';
COMMENT ON COLUMN ds_unified_user.unionid IS '第三方 UnionID';
COMMENT ON COLUMN ds_unified_user.username IS '用户名（系统账户）';
COMMENT ON COLUMN ds_unified_user.phone IS '手机号';
COMMENT ON COLUMN ds_unified_user.email IS '邮箱';
COMMENT ON COLUMN ds_unified_user.nickname IS '昵称';
COMMENT ON COLUMN ds_unified_user.avatar_url IS '头像URL';
COMMENT ON COLUMN ds_unified_user.password_hash IS '密码哈希';
COMMENT ON COLUMN ds_unified_user.password_algo IS '密码哈希算法：BCRYPT/SCrypt/PBKDF2';
COMMENT ON COLUMN ds_unified_user.password_salt IS '密码盐';
COMMENT ON COLUMN ds_unified_user.password_update_time IS '密码更新时间';
COMMENT ON COLUMN ds_unified_user.mfa_enabled IS '是否启用 MFA';
COMMENT ON COLUMN ds_unified_user.mfa_secret_encrypted IS 'MFA 秘钥密文';
COMMENT ON COLUMN ds_unified_user.mfa_bind_time IS 'MFA 绑定时间';
COMMENT ON COLUMN ds_unified_user.phone_verified IS '手机号是否已验证';
COMMENT ON COLUMN ds_unified_user.phone_verified_time IS '手机号验证时间';
COMMENT ON COLUMN ds_unified_user.email_verified IS '邮箱是否已验证';
COMMENT ON COLUMN ds_unified_user.email_verified_time IS '邮箱验证时间';
COMMENT ON COLUMN ds_unified_user.real_name_verified IS '实名是否已认证';
COMMENT ON COLUMN ds_unified_user.real_name_verified_time IS '实名认证时间';
COMMENT ON COLUMN ds_unified_user.status IS '账号状态：0-正常 1-禁用 2-锁定 3-注销';
COMMENT ON COLUMN ds_unified_user.disabled_reason IS '禁用原因';
COMMENT ON COLUMN ds_unified_user.disabled_time IS '禁用时间';
COMMENT ON COLUMN ds_unified_user.failed_login_count IS '连续失败次数';
COMMENT ON COLUMN ds_unified_user.last_failed_login_time IS '上次失败时间';
COMMENT ON COLUMN ds_unified_user.lock_until_time IS '锁定截止时间';
COMMENT ON COLUMN ds_unified_user.last_login_time IS '上次登录时间';
COMMENT ON COLUMN ds_unified_user.last_login_ip IS '上次登录IP';
COMMENT ON COLUMN ds_unified_user.last_login_user_agent IS '上次登录UA';
COMMENT ON COLUMN ds_unified_user.last_login_device_id IS '上次登录设备ID';
COMMENT ON COLUMN ds_unified_user.register_time IS '注册时间';
COMMENT ON COLUMN ds_unified_user.register_ip IS '注册IP';
COMMENT ON COLUMN ds_unified_user.token_version IS '令牌版本号（用于主动失效）';
COMMENT ON COLUMN ds_unified_user.create_time IS '创建时间';
COMMENT ON COLUMN ds_unified_user.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_unified_user_openid_login ON ds_unified_user(openid, login_type);
CREATE INDEX IF NOT EXISTS idx_unified_user_unionid ON ds_unified_user(unionid);
CREATE INDEX IF NOT EXISTS idx_unified_user_username ON ds_unified_user(username);
CREATE INDEX IF NOT EXISTS idx_unified_user_phone ON ds_unified_user(phone);
CREATE INDEX IF NOT EXISTS idx_unified_user_email ON ds_unified_user(email);
CREATE INDEX IF NOT EXISTS idx_unified_user_status ON ds_unified_user(status);
CREATE INDEX IF NOT EXISTS idx_unified_user_create_time ON ds_unified_user(create_time);
CREATE INDEX IF NOT EXISTS idx_unified_user_type_identity ON ds_unified_user(user_type, identity);
CREATE INDEX IF NOT EXISTS idx_unified_user_source_login ON ds_unified_user(source, login_type);

-- ============================================================
-- 服务商表
-- ============================================================

-- 服务商表（含 V21 bug fix 字段 + V2.1.0 alliance_member_flag）
CREATE TABLE IF NOT EXISTS ds_provider (
    id                   BIGSERIAL       PRIMARY KEY,
    provider_no          VARCHAR(64)     NOT NULL,
    user_id              VARCHAR(64)     NOT NULL,
    company_name         VARCHAR(128),
    license_no           VARCHAR(64),
    contact_phone        VARCHAR(20)     NOT NULL,
    contact_name         VARCHAR(32),
    address              VARCHAR(255),
    provider_status      SMALLINT        DEFAULT 1,
    create_time          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    real_name            VARCHAR(50),
    id_card              VARCHAR(18),
    auth_type            SMALLINT        DEFAULT 1,
    lng                  NUMERIC(10, 7),
    lat                  NUMERIC(10, 7),
    alliance_member_flag BOOLEAN         DEFAULT FALSE NOT NULL
);

COMMENT ON TABLE ds_provider IS '服务商表';
COMMENT ON COLUMN ds_provider.id IS '服务商唯一标识（自增主键）';
COMMENT ON COLUMN ds_provider.provider_no IS '服务商业务编号（唯一）';
COMMENT ON COLUMN ds_provider.user_id IS '关联统一用户ID';
COMMENT ON COLUMN ds_provider.company_name IS '公司名称（企业认证时）';
COMMENT ON COLUMN ds_provider.license_no IS '营业执照编号';
COMMENT ON COLUMN ds_provider.contact_phone IS '联系电话';
COMMENT ON COLUMN ds_provider.contact_name IS '联系人姓名';
COMMENT ON COLUMN ds_provider.address IS '地址';
COMMENT ON COLUMN ds_provider.provider_status IS '服务商状态：0-封禁，1-正常，2-待认证';
COMMENT ON COLUMN ds_provider.create_time IS '创建时间';
COMMENT ON COLUMN ds_provider.update_time IS '更新时间';
COMMENT ON COLUMN ds_provider.real_name IS '真实姓名';
COMMENT ON COLUMN ds_provider.id_card IS '身份证号';
COMMENT ON COLUMN ds_provider.auth_type IS '认证类型：1-个人，2-企业';
COMMENT ON COLUMN ds_provider.lng IS '经度';
COMMENT ON COLUMN ds_provider.lat IS '纬度';
COMMENT ON COLUMN ds_provider.alliance_member_flag IS '联盟成员标志';

CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_provider_provider_no ON ds_provider(provider_no);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_provider_license_no ON ds_provider(license_no);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_provider_sys_user_id ON ds_provider(user_id);

-- 服务商认证/资质信息表（含 V21 bug fix 字段）
CREATE TABLE IF NOT EXISTS ds_provider_auth (
    id                BIGSERIAL       PRIMARY KEY,
    provider_no       VARCHAR(64)     NOT NULL,
    cert_type         SMALLINT        NOT NULL,
    cert_name         VARCHAR(128)    NOT NULL,
    cert_no           VARCHAR(64),
    cert_level        VARCHAR(32),
    issuing_authority VARCHAR(128),
    issue_date        DATE,
    expire_date       DATE,
    cert_material     VARCHAR(1024)   DEFAULT '',
    audit_status      SMALLINT        DEFAULT 0,
    audit_opinion     VARCHAR(255),
    auditor_id        BIGINT,
    create_time       TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    audit_time        TIMESTAMP,
    id_card_front     VARCHAR(500),
    id_card_back      VARCHAR(500),
    pilot_license     VARCHAR(500),
    business_license  VARCHAR(500),
    other_materials   TEXT,
    update_time       TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_provider_auth IS '服务商认证/资质信息表';
COMMENT ON COLUMN ds_provider_auth.id IS '认证记录唯一标识（自增主键）';
COMMENT ON COLUMN ds_provider_auth.provider_no IS '服务商编号，关联ds_provider.provider_no';
COMMENT ON COLUMN ds_provider_auth.cert_type IS '认证类型（如无人机证书、经营资质等）';
COMMENT ON COLUMN ds_provider_auth.cert_name IS '证书名称';
COMMENT ON COLUMN ds_provider_auth.cert_no IS '证书编号（唯一）';
COMMENT ON COLUMN ds_provider_auth.cert_level IS '证书级别/等级';
COMMENT ON COLUMN ds_provider_auth.issuing_authority IS '发证机关';
COMMENT ON COLUMN ds_provider_auth.issue_date IS '签发日期';
COMMENT ON COLUMN ds_provider_auth.expire_date IS '到期日期';
COMMENT ON COLUMN ds_provider_auth.cert_material IS '认证材料（图片/文件URL，多个用逗号分隔）';
COMMENT ON COLUMN ds_provider_auth.audit_status IS '审核状态（0-待审核，1-已通过，2-已拒绝）';
COMMENT ON COLUMN ds_provider_auth.audit_opinion IS '审核意见（拒绝时必填）';
COMMENT ON COLUMN ds_provider_auth.auditor_id IS '审核人ID';
COMMENT ON COLUMN ds_provider_auth.create_time IS '创建时间';
COMMENT ON COLUMN ds_provider_auth.audit_time IS '审核时间';
COMMENT ON COLUMN ds_provider_auth.id_card_front IS '身份证正面URL';
COMMENT ON COLUMN ds_provider_auth.id_card_back IS '身份证反面URL';
COMMENT ON COLUMN ds_provider_auth.pilot_license IS '驾驶员执照URL';
COMMENT ON COLUMN ds_provider_auth.business_license IS '营业执照URL';
COMMENT ON COLUMN ds_provider_auth.other_materials IS '其他资质材料JSON';
COMMENT ON COLUMN ds_provider_auth.update_time IS '更新时间';

CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_provider_auth_cert_no ON ds_provider_auth(cert_no);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_provider_auth_unique ON ds_provider_auth(provider_no, cert_type, cert_no);
CREATE INDEX IF NOT EXISTS idx_ds_provider_auth_provider_id ON ds_provider_auth(provider_no);
CREATE INDEX IF NOT EXISTS idx_provider_auth_provider_no ON ds_provider_auth(provider_no);
CREATE INDEX IF NOT EXISTS idx_provider_auth_audit_status ON ds_provider_auth(audit_status);

-- ============================================================
-- 无人机表
-- ============================================================

-- 无人机型号基础参数表
CREATE TABLE IF NOT EXISTS ds_drone_param (
    id                  BIGSERIAL       PRIMARY KEY,
    drone_model_no      VARCHAR(128)    NOT NULL,
    max_load            NUMERIC(5, 2)   NOT NULL,
    endurance           INTEGER         NOT NULL,
    max_speed           NUMERIC(6, 2),
    battery_capacity    INTEGER,
    weight              NUMERIC(6, 3),
    dimensions          VARCHAR(64),
    rotor_count         SMALLINT,
    wind_resistance_lv  SMALLINT,
    certification_level VARCHAR(32),
    manufacturer_url    VARCHAR(255),
    create_time         TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time         TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE ds_drone_param IS '无人机型号基础参数表';
COMMENT ON COLUMN ds_drone_param.id IS '基础参数唯一标识（自增主键）';
COMMENT ON COLUMN ds_drone_param.drone_model_no IS '机型业务编号（唯一）';
COMMENT ON COLUMN ds_drone_param.max_load IS '最大载重（kg）';
COMMENT ON COLUMN ds_drone_param.endurance IS '续航里程（km）';
COMMENT ON COLUMN ds_drone_param.max_speed IS '最高速度（km/h）';
COMMENT ON COLUMN ds_drone_param.battery_capacity IS '电池容量（mAh）';
COMMENT ON COLUMN ds_drone_param.weight IS '整机重量（kg）';
COMMENT ON COLUMN ds_drone_param.dimensions IS '机体尺寸（长x宽x高）';
COMMENT ON COLUMN ds_drone_param.rotor_count IS '旋翼数量';
COMMENT ON COLUMN ds_drone_param.wind_resistance_lv IS '抗风等级';
COMMENT ON COLUMN ds_drone_param.certification_level IS '认证等级/级别';
COMMENT ON COLUMN ds_drone_param.manufacturer_url IS '厂商页面URL';
COMMENT ON COLUMN ds_drone_param.create_time IS '记录创建时间';
COMMENT ON COLUMN ds_drone_param.update_time IS '信息最后更新时间（自动维护）';

-- 无人机机型表
CREATE TABLE IF NOT EXISTS ds_drone_model (
    id                  BIGSERIAL       PRIMARY KEY,
    drone_model_no      VARCHAR(64)     NOT NULL,
    brand               VARCHAR(32)     NOT NULL,
    model               VARCHAR(32)     NOT NULL,
    classification      SMALLINT,
    rotor_count         SMALLINT,
    max_takeoff_weight  NUMERIC(6, 3),
    dimensions          VARCHAR(64),
    certification_level VARCHAR(32),
    manufacturer_url    VARCHAR(255),
    pricing_type        SMALLINT        NOT NULL,
    price               NUMERIC(10, 2)  NOT NULL,
    description         VARCHAR(512)    DEFAULT '',
    is_active           SMALLINT        DEFAULT 1,
    create_time         TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time         TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE ds_drone_model IS '无人机机型表';
COMMENT ON COLUMN ds_drone_model.id IS '机型唯一标识（自增主键）';
COMMENT ON COLUMN ds_drone_model.drone_model_no IS '机型业务编号（唯一）';
COMMENT ON COLUMN ds_drone_model.brand IS '品牌';
COMMENT ON COLUMN ds_drone_model.model IS '型号';
COMMENT ON COLUMN ds_drone_model.classification IS '机型类型（如多旋翼、固定翼等）';
COMMENT ON COLUMN ds_drone_model.rotor_count IS '旋翼数量';
COMMENT ON COLUMN ds_drone_model.max_takeoff_weight IS '最大起飞重量（kg）';
COMMENT ON COLUMN ds_drone_model.dimensions IS '机体尺寸（长x宽x高）';
COMMENT ON COLUMN ds_drone_model.certification_level IS '适航/认证等级';
COMMENT ON COLUMN ds_drone_model.manufacturer_url IS '厂商页面URL';
COMMENT ON COLUMN ds_drone_model.pricing_type IS '定价类型（1-元/小时，2-一口价）';
COMMENT ON COLUMN ds_drone_model.price IS '价格（单位：元）';
COMMENT ON COLUMN ds_drone_model.description IS '机型描述';
COMMENT ON COLUMN ds_drone_model.is_active IS '是否启用（0-停用，1-启用）';
COMMENT ON COLUMN ds_drone_model.create_time IS '创建时间';
COMMENT ON COLUMN ds_drone_model.update_time IS '最后更新时间（自动维护）';

CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_drone_model_drone_model_no ON ds_drone_model(drone_model_no);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_drone_model_brand_model ON ds_drone_model(brand, model);

-- 无人机设备信息表
CREATE TABLE IF NOT EXISTS ds_drone (
    id                BIGSERIAL       PRIMARY KEY,
    drone_no          VARCHAR(64)     NOT NULL,
    provider_no       VARCHAR(64)     NOT NULL,
    drone_code        VARCHAR(128)    NOT NULL,
    device_no         VARCHAR(128)    NOT NULL,
    drone_model_no    VARCHAR(64)     NOT NULL,
    registration_no   VARCHAR(32),
    drone_auth_status SMALLINT        DEFAULT 0,
    drone_status      SMALLINT        DEFAULT 1,
    create_time       TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time       TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    drone_photo       VARCHAR(255),
    receipt_photo     VARCHAR(255),
    audit_opinion     VARCHAR(255),
    auth_time         TIMESTAMP(6)
);

COMMENT ON TABLE ds_drone IS '无人机设备信息表';
COMMENT ON COLUMN ds_drone.id IS '无人机唯一标识（自增主键）';
COMMENT ON COLUMN ds_drone.drone_no IS '无人机业务编号（唯一）';
COMMENT ON COLUMN ds_drone.provider_no IS '所属机主业务编号';
COMMENT ON COLUMN ds_drone.drone_code IS '无人机系统内部编号（展示用）';
COMMENT ON COLUMN ds_drone.device_no IS '设备编号（厂商提供的唯一标识）';
COMMENT ON COLUMN ds_drone.drone_model_no IS '关联机型业务编号';
COMMENT ON COLUMN ds_drone.registration_no IS '官方注册编号（适航认证后获取）';
COMMENT ON COLUMN ds_drone.drone_auth_status IS '无人机认证状态（0-未认证，1-审核中，2-已认证，3-已拒绝）';
COMMENT ON COLUMN ds_drone.drone_status IS '设备状态（0-封禁，1-正常）';
COMMENT ON COLUMN ds_drone.create_time IS '记录创建时间';
COMMENT ON COLUMN ds_drone.update_time IS '信息最后更新时间（自动维护）';
COMMENT ON COLUMN ds_drone.drone_photo IS '设备照片';
COMMENT ON COLUMN ds_drone.audit_opinion IS '审核意见（拒绝时必填）';
COMMENT ON COLUMN ds_drone.auth_time IS '审核时间';

CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_drone_device_no ON ds_drone(device_no);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_drone_registration_no ON ds_drone(registration_no);
CREATE INDEX IF NOT EXISTS idx_ds_drone_drone_model_no ON ds_drone(drone_model_no);

-- ============================================================
-- 行程/订单表
-- ============================================================

-- 无人机行程表（含 PostGIS 地理位置列 V33）
CREATE TABLE IF NOT EXISTS ds_trip (
    id                   BIGSERIAL       PRIMARY KEY,
    trip_no              VARCHAR(64)     NOT NULL,
    provider_no          VARCHAR(64)     NOT NULL,
    drone_no             VARCHAR(64)     NOT NULL,
    departure_place      VARCHAR(128)    NOT NULL,
    destination_place    VARCHAR(128)    NOT NULL,
    departure_geo        geography(POINT, 4326),
    destination_geo      geography(POINT, 4326),
    departure_time       TIMESTAMP       NOT NULL,
    estimated_start_time TIMESTAMP,
    available_load       NUMERIC(5, 2)   NOT NULL,
    used_load            NUMERIC(5, 2)   DEFAULT 0.00,
    trip_status          SMALLINT        DEFAULT 1,
    remarks              VARCHAR(512)    DEFAULT '',
    cancel_reason        VARCHAR(255),
    cancel_time          TIMESTAMP,
    create_time          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE ds_trip IS '无人机行程表';
COMMENT ON COLUMN ds_trip.id IS '行程唯一标识（自增主键）';
COMMENT ON COLUMN ds_trip.trip_no IS '行程业务编号（唯一）';
COMMENT ON COLUMN ds_trip.provider_no IS '发布行程的机主业务编号';
COMMENT ON COLUMN ds_trip.drone_no IS '执行行程的无人机业务编号';
COMMENT ON COLUMN ds_trip.departure_place IS '出发地详细地址';
COMMENT ON COLUMN ds_trip.destination_place IS '目的地详细地址';
COMMENT ON COLUMN ds_trip.departure_geo IS '出发地地理位置 (PostGIS geography POINT SRID 4326)';
COMMENT ON COLUMN ds_trip.destination_geo IS '目的地地理位置 (PostGIS geography POINT SRID 4326)';
COMMENT ON COLUMN ds_trip.departure_time IS '计划出发时间';
COMMENT ON COLUMN ds_trip.estimated_start_time IS '预计可服务开始时间';
COMMENT ON COLUMN ds_trip.available_load IS '可预约载重（单位：kg）';
COMMENT ON COLUMN ds_trip.used_load IS '已用载重（单位：kg）';
COMMENT ON COLUMN ds_trip.trip_status IS '行程状态（0-草稿，1-已发布，2-已接单，3-已出发，4-已到达，5-已取消）';
COMMENT ON COLUMN ds_trip.remarks IS '机主备注（如禁运物品说明）';
COMMENT ON COLUMN ds_trip.cancel_reason IS '取消原因（仅状态为5时有效）';
COMMENT ON COLUMN ds_trip.cancel_time IS '取消操作时间';
COMMENT ON COLUMN ds_trip.create_time IS '行程创建时间';
COMMENT ON COLUMN ds_trip.update_time IS '信息最后更新时间（自动维护）';

CREATE INDEX IF NOT EXISTS idx_trip_departure_geo ON ds_trip USING GIST (departure_geo);
CREATE INDEX IF NOT EXISTS idx_trip_destination_geo ON ds_trip USING GIST (destination_geo);

-- ============================================================
-- 配送订单表（V4 合并 order_plan → order 后的最终状态）
-- V2.0.0: V1~V30 增量字段 + V33 PostGIS + V28 plan_no
-- V2.1.0: V4 合并重建（含 source/channel/pilot_action/cancellation）
--         V7 补充 pilot_action 字段
-- 注意：ds_order_plan 表已废弃删除（V6），plan_no 保留用于关联历史数据
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_order (
    id                        BIGSERIAL       PRIMARY KEY,
    order_no                  VARCHAR(64)     NOT NULL,
    trip_no                   VARCHAR(64),
    plan_no                   VARCHAR(64),
    user_id                   VARCHAR(64)     NOT NULL,
    provider_no               VARCHAR(64),
    service_type_no           VARCHAR(64),
    service_project_no        VARCHAR(64),
    order_status              VARCHAR(32)     DEFAULT 'DRAFT' NOT NULL,
    review_status             VARCHAR(16),
    cancellation_status       VARCHAR(32)     DEFAULT 'NONE',
    cancellation_request_time TIMESTAMP,
    cancellation_reason       TEXT,
    cancellation_audit_user   VARCHAR(64),
    cancellation_audit_time   TIMESTAMP,
    cancellation_audit_remark TEXT,
    desired_start_time        TIMESTAMP,
    desired_end_time          TIMESTAMP,
    expire_time               TIMESTAMP,
    departure_place           VARCHAR(255),
    departure_geo             geography(POINT, 4326),
    destination_place         VARCHAR(255),
    destination_geo           geography(POINT, 4326),
    estimated_distance_km     NUMERIC(10, 2),
    goods_type                VARCHAR(64),
    goods_weight              NUMERIC(10, 2),
    required_load             NUMERIC(10, 2),
    pricing_type              SMALLINT,
    budget_amount             NUMERIC(12, 2),
    order_amount              NUMERIC(12, 2),
    actual_amount             NUMERIC(12, 2),
    source                    VARCHAR(32),
    channel                   VARCHAR(64),
    remarks                   VARCHAR(512),
    complete_photo            VARCHAR(512),
    pilot_action_status       VARCHAR(32),
    pilot_action_time         TIMESTAMP,
    pilot_action_remark       VARCHAR(512),
    created_time              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_time              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE ds_order IS '配送订单表（V4 合并 order_plan 后的统一订单表）';
COMMENT ON COLUMN ds_order.id IS '订单唯一标识（自增主键）';
COMMENT ON COLUMN ds_order.order_no IS '订单业务编号（唯一）';
COMMENT ON COLUMN ds_order.trip_no IS '关联行程业务编号';
COMMENT ON COLUMN ds_order.plan_no IS '关联的订单计划编号（历史兼容，ds_order_plan 已废弃）';
COMMENT ON COLUMN ds_order.user_id IS '下单用户ID';
COMMENT ON COLUMN ds_order.provider_no IS '机主业务编号，关联ds_provider.provider_no';
COMMENT ON COLUMN ds_order.service_type_no IS '服务类型编号';
COMMENT ON COLUMN ds_order.service_project_no IS '服务项目编号';
COMMENT ON COLUMN ds_order.order_status IS '订单状态：DRAFT-草稿, PENDING-待确认, CONFIRMED-已确认, IN_PROGRESS-进行中, COMPLETED-已完成, CANCELLED-已取消, REJECTED-已拒绝, EXPIRED-已过期';
COMMENT ON COLUMN ds_order.review_status IS '审核状态';
COMMENT ON COLUMN ds_order.cancellation_status IS '取消申请状态: NONE/REQUESTED/APPROVED/REJECTED';
COMMENT ON COLUMN ds_order.cancellation_request_time IS '取消申请时间';
COMMENT ON COLUMN ds_order.cancellation_reason IS '取消理由';
COMMENT ON COLUMN ds_order.cancellation_audit_user IS '取消审核人';
COMMENT ON COLUMN ds_order.cancellation_audit_time IS '取消审核时间';
COMMENT ON COLUMN ds_order.cancellation_audit_remark IS '取消审核备注';
COMMENT ON COLUMN ds_order.desired_start_time IS '期望开始时间';
COMMENT ON COLUMN ds_order.desired_end_time IS '期望结束时间';
COMMENT ON COLUMN ds_order.expire_time IS '订单过期时间';
COMMENT ON COLUMN ds_order.departure_place IS '出发地详细地址';
COMMENT ON COLUMN ds_order.departure_geo IS '出发地地理位置 (PostGIS geography POINT SRID 4326)';
COMMENT ON COLUMN ds_order.destination_place IS '目的地详细地址';
COMMENT ON COLUMN ds_order.destination_geo IS '目的地地理位置 (PostGIS geography POINT SRID 4326)';
COMMENT ON COLUMN ds_order.estimated_distance_km IS '预估距离（单位：km）';
COMMENT ON COLUMN ds_order.goods_type IS '物品类型（如文件、小件商品、食品）';
COMMENT ON COLUMN ds_order.goods_weight IS '物品重量（单位：kg）';
COMMENT ON COLUMN ds_order.required_load IS '所需载重（单位：kg）';
COMMENT ON COLUMN ds_order.pricing_type IS '计价方式';
COMMENT ON COLUMN ds_order.budget_amount IS '预算金额（单位：元）';
COMMENT ON COLUMN ds_order.order_amount IS '订单原价（单位：元）';
COMMENT ON COLUMN ds_order.actual_amount IS '实际支付金额（单位：元）';
COMMENT ON COLUMN ds_order.source IS '订单来源：CUSTOMER-客户发单, B_CHANNEL-渠道发单, C_PILOT-飞手直发';
COMMENT ON COLUMN ds_order.channel IS '订单渠道';
COMMENT ON COLUMN ds_order.remarks IS '备注';
COMMENT ON COLUMN ds_order.complete_photo IS '完成凭证照片URL';
COMMENT ON COLUMN ds_order.pilot_action_status IS '飞手退单操作状态：AWAITING_PILOT-待飞手确认, PILOT_APPROVED-飞手同意, PILOT_REJECTED-飞手拒绝, ADMIN_OVERRIDDEN-管理员介入';
COMMENT ON COLUMN ds_order.pilot_action_time IS '飞手操作时间';
COMMENT ON COLUMN ds_order.pilot_action_remark IS '飞手操作备注';
COMMENT ON COLUMN ds_order.created_time IS '订单创建时间';
COMMENT ON COLUMN ds_order.updated_time IS '更新时间';

CREATE UNIQUE INDEX IF NOT EXISTS uk_ds_order_order_no ON ds_order(order_no);
CREATE INDEX IF NOT EXISTS idx_ds_order_user_id ON ds_order(user_id);
CREATE INDEX IF NOT EXISTS idx_ds_order_provider_no ON ds_order(provider_no);
CREATE INDEX IF NOT EXISTS idx_ds_order_status ON ds_order(order_status);
CREATE INDEX IF NOT EXISTS idx_ds_order_source ON ds_order(source);
CREATE INDEX IF NOT EXISTS idx_ds_order_created_time ON ds_order(created_time DESC);
CREATE INDEX IF NOT EXISTS idx_order_plan_no ON ds_order(plan_no);
CREATE INDEX IF NOT EXISTS idx_order_cancellation_status ON ds_order(cancellation_status);
CREATE INDEX IF NOT EXISTS idx_order_pilot_action_status ON ds_order(pilot_action_status);
CREATE INDEX IF NOT EXISTS idx_order_departure_geo ON ds_order USING GIST (departure_geo);
CREATE INDEX IF NOT EXISTS idx_order_destination_geo ON ds_order USING GIST (destination_geo);

-- ============================================================
-- 订单匹配表（V4 新增，替代 ds_order_plan 的匹配功能）
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_order_match (
    id              BIGSERIAL       PRIMARY KEY,
    match_no        VARCHAR(64)     NOT NULL,
    order_no        VARCHAR(64)     NOT NULL,
    match_status    SMALLINT        NOT NULL DEFAULT 0,
    provider_no     VARCHAR(64),
    drone_model_no  VARCHAR(64),
    drone_no        VARCHAR(64),
    matched_time    TIMESTAMP,
    created_time    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ds_order_match_match_no ON ds_order_match(match_no);
CREATE INDEX IF NOT EXISTS idx_ds_order_match_order_no ON ds_order_match(order_no);
CREATE INDEX IF NOT EXISTS idx_ds_order_match_provider_no ON ds_order_match(provider_no);

-- ============================================================
-- 订单审核表（V4 新增，渠道/联盟订单审批流程）
-- ============================================================
CREATE TABLE IF NOT EXISTS ds_order_audit (
    id              BIGSERIAL       PRIMARY KEY,
    audit_no        VARCHAR(64)     NOT NULL,
    order_no        VARCHAR(64)     NOT NULL,
    audit_type      VARCHAR(32)     NOT NULL,
    audit_status    VARCHAR(16)     NOT NULL,
    applicant_id    VARCHAR(64),
    apply_reason    VARCHAR(512),
    apply_time      TIMESTAMP,
    reviewer_id     VARCHAR(64),
    review_time     TIMESTAMP,
    review_remark   VARCHAR(512),
    created_time    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ds_order_audit_audit_no ON ds_order_audit(audit_no);
CREATE INDEX IF NOT EXISTS idx_ds_order_audit_order_no ON ds_order_audit(order_no);
CREATE INDEX IF NOT EXISTS idx_ds_order_audit_type_status ON ds_order_audit(audit_type, audit_status);

-- 订单改价记录表（V5 重建，去掉 plan_no，改用 order_no）
CREATE TABLE IF NOT EXISTS ds_order_price_change (
    id               BIGSERIAL       PRIMARY KEY,
    order_no         VARCHAR(64)     NOT NULL,
    original_amount  BIGINT          NOT NULL,
    new_amount       BIGINT          NOT NULL,
    diff_amount      BIGINT          NOT NULL,
    reason           TEXT,
    status           SMALLINT        NOT NULL DEFAULT 0,
    provider_no      VARCHAR(64)     NOT NULL,
    customer_no      VARCHAR(64)     NOT NULL,
    approved_time    TIMESTAMP,
    rejected_time    TIMESTAMP,
    reject_reason    TEXT,
    payment_order_no VARCHAR(64),
    created_by       VARCHAR(64)     NOT NULL,
    created_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_order_price_change IS '订单改价记录表';
COMMENT ON COLUMN ds_order_price_change.order_no IS '关联订单编号';
COMMENT ON COLUMN ds_order_price_change.original_amount IS '原金额（分）';
COMMENT ON COLUMN ds_order_price_change.new_amount IS '新金额（分）';
COMMENT ON COLUMN ds_order_price_change.diff_amount IS '差额（分），正数为补差，负数为退差';
COMMENT ON COLUMN ds_order_price_change.status IS '状态：0-待确认 1-已同意 2-已拒绝 3-已超时 4-已取消';
COMMENT ON COLUMN ds_order_price_change.provider_no IS '飞手编号';
COMMENT ON COLUMN ds_order_price_change.customer_no IS '客户编号';
COMMENT ON COLUMN ds_order_price_change.payment_order_no IS '补差支付订单号';

CREATE INDEX IF NOT EXISTS idx_ds_price_change_order_no ON ds_order_price_change(order_no);
CREATE INDEX IF NOT EXISTS idx_ds_price_change_status ON ds_order_price_change(status);

-- 用户认证审核记录表
CREATE TABLE IF NOT EXISTS ds_user_auth (
    id            BIGSERIAL       PRIMARY KEY,
    auth_id       VARCHAR(32)     NOT NULL,
    user_id       VARCHAR(64)     NOT NULL,
    auth_type     SMALLINT        NOT NULL,
    target_id     BIGINT,
    auth_material VARCHAR(512)    NOT NULL,
    audit_status  SMALLINT        DEFAULT 0,
    audit_opinion VARCHAR(255),
    auditor_id    BIGINT,
    create_time   TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    audit_time    TIMESTAMP
);

COMMENT ON TABLE ds_user_auth IS '用户认证审核记录表';
COMMENT ON COLUMN ds_user_auth.id IS '认证记录唯一标识（自增主键）';
COMMENT ON COLUMN ds_user_auth.auth_id IS '认证记录唯一ID';
COMMENT ON COLUMN ds_user_auth.user_id IS '关联用户ID';
COMMENT ON COLUMN ds_user_auth.auth_type IS '认证类型（1-身份证认证，2-无人机认证）';
COMMENT ON COLUMN ds_user_auth.target_id IS '关联目标ID（无人机认证时为drone_id）';
COMMENT ON COLUMN ds_user_auth.auth_material IS '认证材料（图片URL，多个用逗号分隔）';
COMMENT ON COLUMN ds_user_auth.audit_status IS '审核状态（0-待审核，1-已通过，2-已拒绝）';
COMMENT ON COLUMN ds_user_auth.audit_opinion IS '审核意见（拒绝时必填）';
COMMENT ON COLUMN ds_user_auth.auditor_id IS '审核人ID（管理员账号ID）';
COMMENT ON COLUMN ds_user_auth.create_time IS '认证申请提交时间';
COMMENT ON COLUMN ds_user_auth.audit_time IS '审核完成时间';

-- ============================================================
-- 权限系统表 (RBAC+)
-- ============================================================

-- 角色表
CREATE TABLE IF NOT EXISTS ds_role (
    id          BIGSERIAL       PRIMARY KEY,
    role_id     VARCHAR(50)     NOT NULL,
    role_name   VARCHAR(100)    NOT NULL,
    role_code   VARCHAR(50)     NOT NULL,
    description VARCHAR(500),
    is_system   BOOLEAN         DEFAULT FALSE,
    is_active   BOOLEAN         DEFAULT TRUE,
    create_time TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_role_id UNIQUE (role_id),
    CONSTRAINT uk_role_name UNIQUE (role_name),
    CONSTRAINT uk_role_code UNIQUE (role_code),
    CONSTRAINT chk_role_name_not_empty CHECK (role_name <> ''),
    CONSTRAINT chk_role_code_not_empty CHECK (role_code <> '')
);

COMMENT ON TABLE ds_role IS '角色表';
COMMENT ON COLUMN ds_role.role_id IS '角色唯一标识';
COMMENT ON COLUMN ds_role.role_name IS '角色名称';
COMMENT ON COLUMN ds_role.role_code IS '角色编码';
COMMENT ON COLUMN ds_role.description IS '角色描述';
COMMENT ON COLUMN ds_role.is_system IS '是否系统内置角色';
COMMENT ON COLUMN ds_role.is_active IS '角色状态';

CREATE INDEX IF NOT EXISTS idx_role_code ON ds_role(role_code);
CREATE INDEX IF NOT EXISTS idx_role_active ON ds_role(is_active);

-- 权限表
CREATE TABLE IF NOT EXISTS ds_permission (
    id              BIGSERIAL       PRIMARY KEY,
    permission_id   VARCHAR(50)     NOT NULL,
    permission_code VARCHAR(100)    NOT NULL,
    permission_name VARCHAR(100)    NOT NULL,
    resource_type   VARCHAR(50),
    description     VARCHAR(500),
    parent_id       VARCHAR(50),
    sort_order      INTEGER         DEFAULT 0,
    is_active       BOOLEAN         DEFAULT TRUE,
    create_time     TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_permission_id UNIQUE (permission_id),
    CONSTRAINT uk_permission_code UNIQUE (permission_code)
);

COMMENT ON TABLE ds_permission IS '权限表';
COMMENT ON COLUMN ds_permission.permission_id IS '权限唯一标识';
COMMENT ON COLUMN ds_permission.permission_code IS '权限编码';
COMMENT ON COLUMN ds_permission.permission_name IS '权限名称';
COMMENT ON COLUMN ds_permission.resource_type IS '资源类型(menu/button/api)';
COMMENT ON COLUMN ds_permission.parent_id IS '父权限ID';

CREATE INDEX IF NOT EXISTS idx_permission_code ON ds_permission(permission_code);
CREATE INDEX IF NOT EXISTS idx_permission_parent ON ds_permission(parent_id);

-- 角色权限关联表
CREATE TABLE IF NOT EXISTS ds_role_permission (
    id            BIGSERIAL       PRIMARY KEY,
    role_id       VARCHAR(50)     NOT NULL,
    permission_id VARCHAR(50)     NOT NULL,
    create_time   TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_role_permission UNIQUE (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES ds_role(role_id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permission_perm FOREIGN KEY (permission_id) REFERENCES ds_permission(permission_id) ON DELETE CASCADE
);

COMMENT ON TABLE ds_role_permission IS '角色权限关联表';

CREATE INDEX IF NOT EXISTS idx_role_perm_role ON ds_role_permission(role_id);
CREATE INDEX IF NOT EXISTS idx_role_perm_perm ON ds_role_permission(permission_id);

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS ds_user_role (
    id          BIGSERIAL       PRIMARY KEY,
    user_id     VARCHAR(64)     NOT NULL,
    role_id     VARCHAR(50)     NOT NULL,
    create_time TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_role UNIQUE (user_id, role_id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES ds_role(role_id) ON DELETE CASCADE
);

COMMENT ON TABLE ds_user_role IS '用户角色关联表';

CREATE INDEX IF NOT EXISTS idx_user_role_user ON ds_user_role(user_id);
CREATE INDEX IF NOT EXISTS idx_user_role_role ON ds_user_role(role_id);

-- 菜单表
CREATE TABLE IF NOT EXISTS ds_menu (
    id            BIGSERIAL       PRIMARY KEY,
    menu_id       VARCHAR(64)     NOT NULL,
    menu_code     VARCHAR(100)    NOT NULL,
    menu_name     VARCHAR(100)    NOT NULL,
    menu_type     VARCHAR(20)     DEFAULT 'MENU',
    parent_id     VARCHAR(64)     DEFAULT '0',
    path          VARCHAR(200),
    component     VARCHAR(200),
    query         VARCHAR(500),
    is_frame      BOOLEAN         DEFAULT FALSE,
    is_cache      BOOLEAN         DEFAULT TRUE,
    icon          VARCHAR(100),
    sort_order    INTEGER         DEFAULT 0,
    visible       BOOLEAN         DEFAULT TRUE,
    is_active     BOOLEAN         DEFAULT TRUE,
    permission_id VARCHAR(64),
    remark        VARCHAR(500),
    create_by     VARCHAR(64),
    create_time   TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    update_by     VARCHAR(64),
    update_time   TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_menu_id UNIQUE (menu_id),
    CONSTRAINT uk_menu_code UNIQUE (menu_code)
);

COMMENT ON TABLE ds_menu IS '菜单表';
COMMENT ON COLUMN ds_menu.menu_id IS '菜单唯一标识';
COMMENT ON COLUMN ds_menu.menu_code IS '菜单编码';
COMMENT ON COLUMN ds_menu.menu_name IS '菜单名称';
COMMENT ON COLUMN ds_menu.menu_type IS '菜单类型：DIRECTORY-目录, MENU-菜单, BUTTON-按钮';
COMMENT ON COLUMN ds_menu.parent_id IS '父菜单ID，顶级菜单为0';
COMMENT ON COLUMN ds_menu.path IS '路由路径';
COMMENT ON COLUMN ds_menu.component IS '组件路径';
COMMENT ON COLUMN ds_menu.query IS '路由参数';
COMMENT ON COLUMN ds_menu.is_frame IS '是否外链';
COMMENT ON COLUMN ds_menu.is_cache IS '是否缓存';
COMMENT ON COLUMN ds_menu.icon IS '菜单图标';
COMMENT ON COLUMN ds_menu.sort_order IS '显示顺序';
COMMENT ON COLUMN ds_menu.visible IS '菜单状态：true-显示, false-隐藏';
COMMENT ON COLUMN ds_menu.is_active IS '菜单状态：true-启用, false-禁用';
COMMENT ON COLUMN ds_menu.permission_id IS '关联的权限ID';
COMMENT ON COLUMN ds_menu.remark IS '备注';

CREATE INDEX IF NOT EXISTS idx_menu_parent_id ON ds_menu(parent_id);
CREATE INDEX IF NOT EXISTS idx_menu_code ON ds_menu(menu_code);
CREATE INDEX IF NOT EXISTS idx_menu_type ON ds_menu(menu_type);
CREATE INDEX IF NOT EXISTS idx_menu_visible ON ds_menu(visible);
CREATE INDEX IF NOT EXISTS idx_menu_active ON ds_menu(is_active);

-- 角色菜单关联表
CREATE TABLE IF NOT EXISTS ds_role_menu (
    id          BIGSERIAL       PRIMARY KEY,
    role_id     VARCHAR(64)     NOT NULL,
    menu_id     VARCHAR(64)     NOT NULL,
    create_time TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_role_menu UNIQUE (role_id, menu_id)
);

COMMENT ON TABLE ds_role_menu IS '角色菜单关联表';
COMMENT ON COLUMN ds_role_menu.role_id IS '角色ID';
COMMENT ON COLUMN ds_role_menu.menu_id IS '菜单ID';

CREATE INDEX IF NOT EXISTS idx_role_menu_role ON ds_role_menu(role_id);
CREATE INDEX IF NOT EXISTS idx_role_menu_menu ON ds_role_menu(menu_id);

-- 用户特殊权限表
CREATE TABLE IF NOT EXISTS ds_user_permission (
    id            BIGSERIAL       PRIMARY KEY,
    user_id       VARCHAR(64)     NOT NULL,
    permission_id VARCHAR(64)     NOT NULL,
    grant_type    VARCHAR(20)     NOT NULL,
    reason        VARCHAR(500),
    grant_by      VARCHAR(64),
    grant_time    TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    expire_time   TIMESTAMP,
    create_time   TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_user_permission IS '用户特殊权限表，支持单独授予或撤销权限';
COMMENT ON COLUMN ds_user_permission.user_id IS '用户ID';
COMMENT ON COLUMN ds_user_permission.permission_id IS '权限ID';
COMMENT ON COLUMN ds_user_permission.grant_type IS '授权类型：GRANT-授予, REVOKE-撤销';
COMMENT ON COLUMN ds_user_permission.reason IS '授权原因/备注';
COMMENT ON COLUMN ds_user_permission.grant_by IS '授权人';
COMMENT ON COLUMN ds_user_permission.grant_time IS '授权时间';
COMMENT ON COLUMN ds_user_permission.expire_time IS '过期时间（null表示永久）';

CREATE INDEX IF NOT EXISTS idx_user_perm_user ON ds_user_permission(user_id);
CREATE INDEX IF NOT EXISTS idx_user_perm_permission ON ds_user_permission(permission_id);
CREATE INDEX IF NOT EXISTS idx_user_perm_grant_type ON ds_user_permission(grant_type);
CREATE INDEX IF NOT EXISTS idx_user_perm_expire ON ds_user_permission(expire_time);

-- ============================================================
-- 营销活动表
-- ============================================================

-- 优惠券表
CREATE TABLE IF NOT EXISTS ds_coupon (
    id                      BIGSERIAL       PRIMARY KEY,
    coupon_no               VARCHAR(64)     NOT NULL,
    name                    VARCHAR(100)    NOT NULL,
    coupon_type             VARCHAR(20)     DEFAULT 'CASH' NOT NULL,
    amount                  NUMERIC(10, 2)  DEFAULT 0.00 NOT NULL,
    threshold               NUMERIC(10, 2)  DEFAULT 0.00 NOT NULL,
    discount_rate           NUMERIC(5, 2),
    applicable_service_type VARCHAR(64),
    total_count             INTEGER,
    claimed_count           INTEGER         DEFAULT 0 NOT NULL,
    used_count              INTEGER         DEFAULT 0 NOT NULL,
    start_time              TIMESTAMP,
    end_time                TIMESTAMP,
    status                  VARCHAR(20)     DEFAULT 'ACTIVE' NOT NULL,
    description             TEXT,
    tag                     VARCHAR(50),
    create_time             TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time             TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by               VARCHAR(64),
    update_by               VARCHAR(64),
    CONSTRAINT uk_coupon_no UNIQUE (coupon_no)
);

COMMENT ON TABLE ds_coupon IS '优惠券表';
COMMENT ON COLUMN ds_coupon.id IS '主键ID';
COMMENT ON COLUMN ds_coupon.coupon_no IS '优惠券业务编号';
COMMENT ON COLUMN ds_coupon.name IS '优惠券名称';
COMMENT ON COLUMN ds_coupon.coupon_type IS '优惠券类型: DISCOUNT(折扣券), CASH(代金券), FULL_REDUCTION(满减券)';
COMMENT ON COLUMN ds_coupon.amount IS '优惠金额（元）';
COMMENT ON COLUMN ds_coupon.threshold IS '使用门槛金额（元），0表示无门槛';
COMMENT ON COLUMN ds_coupon.discount_rate IS '折扣率（仅折扣券使用，如0.80表示8折）';
COMMENT ON COLUMN ds_coupon.applicable_service_type IS '适用服务类型编号，为空表示全平台通用';
COMMENT ON COLUMN ds_coupon.total_count IS '发行总数量，NULL表示不限量';
COMMENT ON COLUMN ds_coupon.claimed_count IS '已领取数量';
COMMENT ON COLUMN ds_coupon.used_count IS '已使用数量';
COMMENT ON COLUMN ds_coupon.start_time IS '有效期开始时间';
COMMENT ON COLUMN ds_coupon.end_time IS '有效期结束时间';
COMMENT ON COLUMN ds_coupon.status IS '优惠券状态: ACTIVE(激活), INACTIVE(停用), EXPIRED(已过期)';
COMMENT ON COLUMN ds_coupon.description IS '描述';
COMMENT ON COLUMN ds_coupon.tag IS '标签（如：限时、无门槛）';
COMMENT ON COLUMN ds_coupon.create_time IS '创建时间';
COMMENT ON COLUMN ds_coupon.update_time IS '更新时间';
COMMENT ON COLUMN ds_coupon.create_by IS '创建人';
COMMENT ON COLUMN ds_coupon.update_by IS '更新人';

CREATE INDEX IF NOT EXISTS idx_coupon_no ON ds_coupon(coupon_no);
CREATE INDEX IF NOT EXISTS idx_coupon_status ON ds_coupon(status);
CREATE INDEX IF NOT EXISTS idx_coupon_time ON ds_coupon(start_time, end_time);
CREATE INDEX IF NOT EXISTS idx_coupon_type ON ds_coupon(coupon_type);

-- 用户优惠券关联表
CREATE TABLE IF NOT EXISTS ds_user_coupon (
    id             BIGSERIAL       PRIMARY KEY,
    user_coupon_no VARCHAR(64)     NOT NULL,
    customer_no    VARCHAR(64)     NOT NULL,
    coupon_no      VARCHAR(64)     NOT NULL,
    status         VARCHAR(20)     DEFAULT 'UNUSED' NOT NULL,
    receive_time   TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    use_time       TIMESTAMP,
    order_no       VARCHAR(64),
    expire_time    TIMESTAMP,
    source         VARCHAR(20)     DEFAULT 'SYSTEM' NOT NULL,
    create_time    TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time    TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uk_user_coupon_no UNIQUE (user_coupon_no)
);

COMMENT ON TABLE ds_user_coupon IS '用户优惠券关联表';
COMMENT ON COLUMN ds_user_coupon.id IS '主键ID';
COMMENT ON COLUMN ds_user_coupon.user_coupon_no IS '用户优惠券业务编号';
COMMENT ON COLUMN ds_user_coupon.customer_no IS '用户编号';
COMMENT ON COLUMN ds_user_coupon.coupon_no IS '优惠券编号';
COMMENT ON COLUMN ds_user_coupon.status IS '优惠券状态: UNUSED(未使用), USED(已使用), EXPIRED(已过期)';
COMMENT ON COLUMN ds_user_coupon.receive_time IS '领取时间';
COMMENT ON COLUMN ds_user_coupon.use_time IS '使用时间';
COMMENT ON COLUMN ds_user_coupon.order_no IS '关联订单编号';
COMMENT ON COLUMN ds_user_coupon.expire_time IS '过期时间';
COMMENT ON COLUMN ds_user_coupon.source IS '来源: EXCHANGE(兑换), ACTIVITY(活动领取), SYSTEM(系统发放)';
COMMENT ON COLUMN ds_user_coupon.create_time IS '创建时间';
COMMENT ON COLUMN ds_user_coupon.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_user_coupon_customer ON ds_user_coupon(customer_no);
CREATE INDEX IF NOT EXISTS idx_user_coupon_status ON ds_user_coupon(customer_no, status);
CREATE INDEX IF NOT EXISTS idx_user_coupon_expire ON ds_user_coupon(expire_time);
CREATE INDEX IF NOT EXISTS idx_user_coupon_coupon_no ON ds_user_coupon(coupon_no);
CREATE INDEX IF NOT EXISTS idx_user_coupon_order ON ds_user_coupon(order_no);

-- 兑换码表
CREATE TABLE IF NOT EXISTS ds_exchange_code (
    id          BIGSERIAL       PRIMARY KEY,
    code        VARCHAR(32)     NOT NULL,
    coupon_no   VARCHAR(64)     NOT NULL,
    status      VARCHAR(20)     DEFAULT 'ACTIVE' NOT NULL,
    used_by     VARCHAR(64),
    used_at     TIMESTAMP,
    expire_at   TIMESTAMP       NOT NULL,
    batch_no    VARCHAR(64),
    create_time TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by   VARCHAR(64),
    CONSTRAINT uk_exchange_code UNIQUE (code)
);

COMMENT ON TABLE ds_exchange_code IS '兑换码表';
COMMENT ON COLUMN ds_exchange_code.id IS '主键ID';
COMMENT ON COLUMN ds_exchange_code.code IS '兑换码';
COMMENT ON COLUMN ds_exchange_code.coupon_no IS '优惠券编号';
COMMENT ON COLUMN ds_exchange_code.status IS '兑换码状态: ACTIVE(激活), USED(已使用), EXPIRED(已过期)';
COMMENT ON COLUMN ds_exchange_code.used_by IS '使用者用户编号';
COMMENT ON COLUMN ds_exchange_code.used_at IS '使用时间';
COMMENT ON COLUMN ds_exchange_code.expire_at IS '过期时间';
COMMENT ON COLUMN ds_exchange_code.batch_no IS '批次号';
COMMENT ON COLUMN ds_exchange_code.create_time IS '创建时间';
COMMENT ON COLUMN ds_exchange_code.update_time IS '更新时间';
COMMENT ON COLUMN ds_exchange_code.create_by IS '创建人';

CREATE INDEX IF NOT EXISTS idx_exchange_status ON ds_exchange_code(status);
CREATE INDEX IF NOT EXISTS idx_exchange_batch ON ds_exchange_code(batch_no);
CREATE INDEX IF NOT EXISTS idx_exchange_coupon ON ds_exchange_code(coupon_no);
CREATE INDEX IF NOT EXISTS idx_exchange_expire ON ds_exchange_code(expire_at);

-- 红包定义表
CREATE TABLE IF NOT EXISTS ds_red_packet (
    id               BIGSERIAL       PRIMARY KEY,
    red_packet_no    VARCHAR(64)     NOT NULL,
    name             VARCHAR(100)    NOT NULL,
    packet_type      VARCHAR(20)     DEFAULT 'NORMAL' NOT NULL,
    total_amount     NUMERIC(10, 2)  NOT NULL,
    total_count      INTEGER         NOT NULL,
    remaining_amount NUMERIC(10, 2)  NOT NULL,
    remaining_count  INTEGER         DEFAULT 0 NOT NULL,
    min_amount       NUMERIC(10, 2)  DEFAULT 0.01 NOT NULL,
    max_amount       NUMERIC(10, 2),
    expire_time      TIMESTAMP,
    status           VARCHAR(20)     DEFAULT 'ACTIVE' NOT NULL,
    description      TEXT,
    source_type      VARCHAR(20)     DEFAULT 'SYSTEM' NOT NULL,
    source_ref       VARCHAR(64),
    create_time      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by        VARCHAR(64),
    update_by        VARCHAR(64),
    CONSTRAINT uk_red_packet_no UNIQUE (red_packet_no)
);

COMMENT ON TABLE ds_red_packet IS '红包定义表';
COMMENT ON COLUMN ds_red_packet.id IS '主键ID';
COMMENT ON COLUMN ds_red_packet.red_packet_no IS '红包业务编号';
COMMENT ON COLUMN ds_red_packet.name IS '红包名称';
COMMENT ON COLUMN ds_red_packet.packet_type IS '红包类型: NORMAL(普通红包), LUCKY(拼手气红包), FIXED(固定金额红包), ACTIVITY(活动红包)';
COMMENT ON COLUMN ds_red_packet.total_amount IS '红包总金额（元）';
COMMENT ON COLUMN ds_red_packet.total_count IS '红包总个数';
COMMENT ON COLUMN ds_red_packet.remaining_amount IS '剩余金额（元）';
COMMENT ON COLUMN ds_red_packet.remaining_count IS '剩余个数';
COMMENT ON COLUMN ds_red_packet.min_amount IS '最小金额（元）';
COMMENT ON COLUMN ds_red_packet.max_amount IS '最大金额（元）';
COMMENT ON COLUMN ds_red_packet.expire_time IS '过期时间';
COMMENT ON COLUMN ds_red_packet.status IS '红包状态: ACTIVE(激活), FINISHED(已抢完), EXPIRED(已过期), CANCELLED(已取消)';
COMMENT ON COLUMN ds_red_packet.description IS '红包描述';
COMMENT ON COLUMN ds_red_packet.source_type IS '来源类型: SYSTEM/ACTIVITY/REFERRAL/TASK';
COMMENT ON COLUMN ds_red_packet.source_ref IS '来源关联ID';
COMMENT ON COLUMN ds_red_packet.create_time IS '创建时间';
COMMENT ON COLUMN ds_red_packet.update_time IS '更新时间';
COMMENT ON COLUMN ds_red_packet.create_by IS '创建人';
COMMENT ON COLUMN ds_red_packet.update_by IS '更新人';

CREATE INDEX IF NOT EXISTS idx_red_packet_no ON ds_red_packet(red_packet_no);
CREATE INDEX IF NOT EXISTS idx_red_packet_status ON ds_red_packet(status);
CREATE INDEX IF NOT EXISTS idx_red_packet_type ON ds_red_packet(packet_type);
CREATE INDEX IF NOT EXISTS idx_red_packet_expire ON ds_red_packet(expire_time);
CREATE INDEX IF NOT EXISTS idx_red_packet_source ON ds_red_packet(source_type, source_ref);

-- 用户红包记录表
CREATE TABLE IF NOT EXISTS ds_user_red_packet (
    id                 BIGSERIAL       PRIMARY KEY,
    user_red_packet_no VARCHAR(64)     NOT NULL,
    customer_no        VARCHAR(64)     NOT NULL,
    red_packet_no      VARCHAR(64)     NOT NULL,
    amount             NUMERIC(10, 2)  NOT NULL,
    status             VARCHAR(20)     DEFAULT 'RECEIVED' NOT NULL,
    receive_time       TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    convert_time       TIMESTAMP,
    expire_time        TIMESTAMP,
    source_type        VARCHAR(20)     DEFAULT 'SYSTEM' NOT NULL,
    source_ref         VARCHAR(64),
    remark             VARCHAR(200),
    create_time        TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time        TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uk_user_red_packet_no UNIQUE (user_red_packet_no)
);

COMMENT ON TABLE ds_user_red_packet IS '用户红包记录表';
COMMENT ON COLUMN ds_user_red_packet.id IS '主键ID';
COMMENT ON COLUMN ds_user_red_packet.user_red_packet_no IS '用户红包业务编号';
COMMENT ON COLUMN ds_user_red_packet.customer_no IS '用户编号';
COMMENT ON COLUMN ds_user_red_packet.red_packet_no IS '红包编号';
COMMENT ON COLUMN ds_user_red_packet.amount IS '红包金额（元）';
COMMENT ON COLUMN ds_user_red_packet.status IS '状态: RECEIVED(已领取), CONVERTED(已兑换), EXPIRED(已过期)';
COMMENT ON COLUMN ds_user_red_packet.receive_time IS '领取时间';
COMMENT ON COLUMN ds_user_red_packet.convert_time IS '兑换时间';
COMMENT ON COLUMN ds_user_red_packet.expire_time IS '过期时间';
COMMENT ON COLUMN ds_user_red_packet.source_type IS '来源类型';
COMMENT ON COLUMN ds_user_red_packet.source_ref IS '来源关联ID';
COMMENT ON COLUMN ds_user_red_packet.remark IS '备注';
COMMENT ON COLUMN ds_user_red_packet.create_time IS '创建时间';
COMMENT ON COLUMN ds_user_red_packet.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_user_red_packet_customer ON ds_user_red_packet(customer_no);
CREATE INDEX IF NOT EXISTS idx_user_red_packet_status ON ds_user_red_packet(customer_no, status);
CREATE INDEX IF NOT EXISTS idx_user_red_packet_expire ON ds_user_red_packet(expire_time);
CREATE INDEX IF NOT EXISTS idx_user_red_packet_ref ON ds_user_red_packet(red_packet_no);
CREATE INDEX IF NOT EXISTS idx_user_red_packet_source ON ds_user_red_packet(source_type, source_ref);

-- 红包兑换记录表
CREATE TABLE IF NOT EXISTS ds_red_packet_convert (
    id                 BIGSERIAL       PRIMARY KEY,
    convert_no         VARCHAR(64)     NOT NULL,
    customer_no        VARCHAR(64)     NOT NULL,
    user_red_packet_no VARCHAR(64)     NOT NULL,
    convert_amount     NUMERIC(10, 2)  NOT NULL,
    before_balance     NUMERIC(10, 2)  NOT NULL,
    after_balance      NUMERIC(10, 2)  NOT NULL,
    convert_time       TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    status             VARCHAR(20)     DEFAULT 'SUCCESS' NOT NULL,
    remark             VARCHAR(200),
    create_time        TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uk_red_packet_convert_no UNIQUE (convert_no)
);

COMMENT ON TABLE ds_red_packet_convert IS '红包兑换记录表';
COMMENT ON COLUMN ds_red_packet_convert.id IS '主键ID';
COMMENT ON COLUMN ds_red_packet_convert.convert_no IS '兑换业务编号';
COMMENT ON COLUMN ds_red_packet_convert.customer_no IS '用户编号';
COMMENT ON COLUMN ds_red_packet_convert.user_red_packet_no IS '用户红包编号';
COMMENT ON COLUMN ds_red_packet_convert.convert_amount IS '兑换金额（元）';
COMMENT ON COLUMN ds_red_packet_convert.before_balance IS '兑换前余额（元）';
COMMENT ON COLUMN ds_red_packet_convert.after_balance IS '兑换后余额（元）';
COMMENT ON COLUMN ds_red_packet_convert.convert_time IS '兑换时间';
COMMENT ON COLUMN ds_red_packet_convert.status IS '兑换状态: SUCCESS(成功), FAILED(失败), PROCESSING(处理中)';
COMMENT ON COLUMN ds_red_packet_convert.remark IS '备注';
COMMENT ON COLUMN ds_red_packet_convert.create_time IS '创建时间';

CREATE INDEX IF NOT EXISTS idx_red_packet_convert_no ON ds_red_packet_convert(convert_no);
CREATE INDEX IF NOT EXISTS idx_red_packet_convert_customer ON ds_red_packet_convert(customer_no);
CREATE INDEX IF NOT EXISTS idx_red_packet_convert_time ON ds_red_packet_convert(convert_time);
CREATE INDEX IF NOT EXISTS idx_red_packet_convert_ref ON ds_red_packet_convert(user_red_packet_no);

-- ============================================================
-- 服务目录
-- ============================================================

-- 服务类型表
CREATE TABLE IF NOT EXISTS ds_service_type (
    id          BIGSERIAL PRIMARY KEY,
    type_no     VARCHAR(64)      NOT NULL,
    name        VARCHAR(64)      NOT NULL,
    code        VARCHAR(32)      NOT NULL,
    category    SMALLINT,
    description VARCHAR(512)     DEFAULT '',
    is_active   SMALLINT         DEFAULT 1,
    create_time TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_service_type IS '服务类型表';
COMMENT ON COLUMN ds_service_type.id IS '服务类型唯一标识（自增主键）';
COMMENT ON COLUMN ds_service_type.type_no IS '服务类型业务编号（唯一）';
COMMENT ON COLUMN ds_service_type.name IS '服务类型名称（唯一）';
COMMENT ON COLUMN ds_service_type.code IS '服务类型编码（唯一）';
COMMENT ON COLUMN ds_service_type.category IS '服务类别（如配送、勘察等）';
COMMENT ON COLUMN ds_service_type.description IS '服务描述';
COMMENT ON COLUMN ds_service_type.is_active IS '是否启用（0-禁用，1-启用）';
COMMENT ON COLUMN ds_service_type.create_time IS '创建时间';
COMMENT ON COLUMN ds_service_type.update_time IS '最后更新时间（自动维护）';

CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_service_type_code ON ds_service_type (code);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_service_type_name ON ds_service_type (name);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_service_type_service_type_no ON ds_service_type (type_no);

-- 服务项目表
CREATE TABLE IF NOT EXISTS ds_service_project (
    id            BIGSERIAL PRIMARY KEY,
    project_no    VARCHAR(64)      NOT NULL,
    type_no       VARCHAR(64)      NOT NULL,
    project_name  VARCHAR(64)      NOT NULL,
    project_code  VARCHAR(32)      NOT NULL,
    project_price NUMERIC(10, 2)   NOT NULL,
    unit          VARCHAR(32)      NOT NULL,
    description   VARCHAR(512)     DEFAULT '',
    is_active     SMALLINT         DEFAULT 1,
    create_time   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_service_project IS '服务项目表';
COMMENT ON COLUMN ds_service_project.id IS '服务项目唯一标识（自增主键）';
COMMENT ON COLUMN ds_service_project.project_no IS '服务项目业务ID（唯一）';
COMMENT ON COLUMN ds_service_project.type_no IS '服务类型业务ID（外键关联ds_service_type表）';
COMMENT ON COLUMN ds_service_project.project_name IS '服务项目名称（唯一）';
COMMENT ON COLUMN ds_service_project.project_code IS '服务项目编码（唯一）';
COMMENT ON COLUMN ds_service_project.project_price IS '服务项目价格（单位：元，保留2位小数）';
COMMENT ON COLUMN ds_service_project.unit IS '价格单位（unit，如：元/小时、次/单）';
COMMENT ON COLUMN ds_service_project.description IS '服务描述（description）';
COMMENT ON COLUMN ds_service_project.is_active IS '是否启用（is_active，0-禁用，1-启用）';
COMMENT ON COLUMN ds_service_project.create_time IS '创建时间（create_time）';
COMMENT ON COLUMN ds_service_project.update_time IS '最后更新时间（update_time，自动维护）';

CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_service_project_project_code ON ds_service_project (project_code);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_service_project_project_name ON ds_service_project (project_name);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ds_service_project_service_project_no ON ds_service_project (project_no);
CREATE INDEX IF NOT EXISTS idx_ds_service_project_service_type_no ON ds_service_project (type_no);

-- 服务项目机型关联表（V5 新增）
CREATE TABLE IF NOT EXISTS ds_service_project_machine (
    id                  BIGSERIAL       PRIMARY KEY,
    service_project_no  VARCHAR(64)     NOT NULL,
    dm_no               VARCHAR(64)     NOT NULL,
    created_time        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ds_spm_project_no ON ds_service_project_machine(service_project_no);
CREATE INDEX IF NOT EXISTS idx_ds_spm_dm_no ON ds_service_project_machine(dm_no);

COMMENT ON TABLE ds_service_project_machine IS '服务项目机型关联表';

-- 客户自定义服务项目表
CREATE TABLE IF NOT EXISTS ds_customized_service_project (
    id             BIGSERIAL PRIMARY KEY,
    project_no     VARCHAR(64)      NOT NULL,
    type_name      VARCHAR(255)     NOT NULL,
    project_name   VARCHAR(255)     NOT NULL,
    project_budget NUMERIC(10, 2)   NOT NULL,
    description    VARCHAR(512)     DEFAULT '',
    create_time    TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_customized_service_project IS '客户自定义服务项目表';
COMMENT ON COLUMN ds_customized_service_project.id IS '服务项目唯一标识（自增主键）';
COMMENT ON COLUMN ds_customized_service_project.project_no IS '服务项目业务ID（唯一）';
COMMENT ON COLUMN ds_customized_service_project.type_name IS '服务类型业务';
COMMENT ON COLUMN ds_customized_service_project.project_name IS '服务项目名称';
COMMENT ON COLUMN ds_customized_service_project.project_budget IS '项目预算';
COMMENT ON COLUMN ds_customized_service_project.description IS '服务描述（description）';
COMMENT ON COLUMN ds_customized_service_project.create_time IS '创建时间（create_time）';
COMMENT ON COLUMN ds_customized_service_project.update_time IS '最后更新时间（update_time，自动维护）';

-- ============================================================
-- 飞手相关
-- ============================================================

-- 飞手意见反馈表
CREATE TABLE IF NOT EXISTS ds_pilot_feedback (
    id            BIGSERIAL PRIMARY KEY,
    feedback_no   VARCHAR(32)      NOT NULL UNIQUE,
    provider_no   VARCHAR(64),
    user_id       VARCHAR(64)      NOT NULL,
    feedback_type VARCHAR(20)      NOT NULL,
    content       TEXT             NOT NULL,
    images        TEXT,
    contact_info  VARCHAR(100),
    status        SMALLINT         DEFAULT 0,
    reply         TEXT,
    handler       VARCHAR(32),
    handle_time   TIMESTAMP,
    create_time   TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP        DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_pilot_feedback IS '飞手意见反馈表';
COMMENT ON COLUMN ds_pilot_feedback.id IS '主键ID';
COMMENT ON COLUMN ds_pilot_feedback.feedback_no IS '反馈编号';
COMMENT ON COLUMN ds_pilot_feedback.provider_no IS '飞手编号';
COMMENT ON COLUMN ds_pilot_feedback.user_id IS '用户ID';
COMMENT ON COLUMN ds_pilot_feedback.feedback_type IS '反馈类型: SUGGESTION-建议, BUG-问题反馈, COMPLAINT-投诉, OTHER-其他';
COMMENT ON COLUMN ds_pilot_feedback.content IS '反馈内容';
COMMENT ON COLUMN ds_pilot_feedback.images IS '反馈图片JSON数组';
COMMENT ON COLUMN ds_pilot_feedback.contact_info IS '联系方式';
COMMENT ON COLUMN ds_pilot_feedback.status IS '处理状态: 0-待处理, 1-已处理, 2-已关闭';
COMMENT ON COLUMN ds_pilot_feedback.reply IS '处理回复';
COMMENT ON COLUMN ds_pilot_feedback.handler IS '处理人';
COMMENT ON COLUMN ds_pilot_feedback.handle_time IS '处理时间';
COMMENT ON COLUMN ds_pilot_feedback.create_time IS '创建时间';
COMMENT ON COLUMN ds_pilot_feedback.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_pilot_feedback_user_id ON ds_pilot_feedback (user_id);
CREATE INDEX IF NOT EXISTS idx_pilot_feedback_create_time ON ds_pilot_feedback (create_time DESC);

-- 飞手结算记录表
CREATE TABLE IF NOT EXISTS ds_pilot_settlement (
    id              BIGSERIAL PRIMARY KEY,
    plan_no         VARCHAR(64)      NOT NULL,
    provider_no     VARCHAR(64)      NOT NULL,
    pilot_user_id   VARCHAR(64)      NOT NULL,
    order_amount    DECIMAL(12, 2)   NOT NULL DEFAULT 0,
    pilot_income    DECIMAL(12, 2)   NOT NULL DEFAULT 0,
    platform_fee    DECIMAL(12, 2)   NOT NULL DEFAULT 0,
    status          VARCHAR(32)      NOT NULL DEFAULT 'PENDING',
    retry_count     INT              NOT NULL DEFAULT 0,
    max_retry       INT              NOT NULL DEFAULT 5,
    next_retry_time TIMESTAMP,
    error_message   VARCHAR(500),
    create_time     TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_settlement_plan_no UNIQUE (plan_no)
);

CREATE INDEX IF NOT EXISTS idx_settlement_status ON ds_pilot_settlement (status);
CREATE INDEX IF NOT EXISTS idx_settlement_provider ON ds_pilot_settlement (provider_no);

COMMENT ON TABLE ds_pilot_settlement IS '飞手结算记录表';
COMMENT ON COLUMN ds_pilot_settlement.status IS '结算状态: PENDING/SUCCESS/FAILED';
COMMENT ON COLUMN ds_pilot_settlement.order_amount IS '实际支付金额（元）';
COMMENT ON COLUMN ds_pilot_settlement.pilot_income IS '飞手收入（元）';
COMMENT ON COLUMN ds_pilot_settlement.platform_fee IS '平台服务费（元）';

-- 结算 Outbox 表
CREATE TABLE IF NOT EXISTS ds_settlement_outbox (
    id              BIGSERIAL PRIMARY KEY,
    message_id      VARCHAR(64)      NOT NULL,
    routing_key     VARCHAR(128)     NOT NULL,
    payload         TEXT             NOT NULL,
    status          VARCHAR(16)      NOT NULL DEFAULT 'PENDING',
    retry_count     INT              NOT NULL DEFAULT 0,
    max_retry       INT              NOT NULL DEFAULT 5,
    next_retry_time TIMESTAMP,
    error_message   TEXT,
    create_time     TIMESTAMP        NOT NULL DEFAULT NOW(),
    update_time     TIMESTAMP        NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_settlement_outbox_message_id UNIQUE (message_id),
    CONSTRAINT chk_settlement_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_settlement_outbox_status_retry ON ds_settlement_outbox (status, next_retry_time);

COMMENT ON TABLE ds_settlement_outbox IS '结算Outbox表，将Feign同步调用改为异步MQ，保证最终一致性';
COMMENT ON COLUMN ds_settlement_outbox.status IS 'PENDING-待发送, SENT-已发送, FAILED-发送失败';
COMMENT ON COLUMN ds_settlement_outbox.message_id IS '消息唯一ID，用于幂等防重';

-- ============================================================
-- 钱包/支付系统（V6 + V14 + V15 + V23 + V34 + V35）
-- ============================================================

-- 用户钱包表（含 V34 CHECK 约束 + V35 balance_hash）
CREATE TABLE IF NOT EXISTS ds_wallet (
    id              SERIAL          PRIMARY KEY,
    wallet_no       VARCHAR(64)     NOT NULL UNIQUE,
    customer_no     VARCHAR(64)     NOT NULL,
    balance         BIGINT          NOT NULL DEFAULT 0,
    frozen_balance  BIGINT          NOT NULL DEFAULT 0,
    total_recharge  BIGINT          NOT NULL DEFAULT 0,
    total_withdraw  BIGINT          NOT NULL DEFAULT 0,
    total_consume   BIGINT          NOT NULL DEFAULT 0,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    version         INTEGER         NOT NULL DEFAULT 0,
    balance_hash    VARCHAR(128),
    create_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_wallet_balance_nonneg CHECK (balance >= 0),
    CONSTRAINT chk_wallet_frozen_nonneg CHECK (frozen_balance >= 0),
    CONSTRAINT chk_wallet_totals_nonneg CHECK (total_recharge >= 0 AND total_withdraw >= 0 AND total_consume >= 0)
);

COMMENT ON TABLE ds_wallet IS '用户钱包表，存储用户钱包基本信息和余额';
COMMENT ON COLUMN ds_wallet.id IS '主键ID';
COMMENT ON COLUMN ds_wallet.wallet_no IS '钱包编号，唯一标识';
COMMENT ON COLUMN ds_wallet.customer_no IS '用户编号';
COMMENT ON COLUMN ds_wallet.balance IS '可用余额（分）';
COMMENT ON COLUMN ds_wallet.frozen_balance IS '冻结余额（分）';
COMMENT ON COLUMN ds_wallet.total_recharge IS '累计充值金额（分）';
COMMENT ON COLUMN ds_wallet.total_withdraw IS '累计提现金额（分）';
COMMENT ON COLUMN ds_wallet.total_consume IS '累计消费金额（分）';
COMMENT ON COLUMN ds_wallet.status IS '钱包状态: ACTIVE(正常), FROZEN(冻结), CLOSED(关闭)';
COMMENT ON COLUMN ds_wallet.version IS '版本号，用于乐观锁控制';
COMMENT ON COLUMN ds_wallet.balance_hash IS '余额哈希校验值（HMAC-SHA256），用于检测余额是否被直接篡改。由应用层计算，数据库不参与密钥运算。';
COMMENT ON COLUMN ds_wallet.create_time IS '创建时间';
COMMENT ON COLUMN ds_wallet.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_wallet_customer_no ON ds_wallet(customer_no);
CREATE INDEX IF NOT EXISTS idx_wallet_status ON ds_wallet(status);
CREATE INDEX IF NOT EXISTS idx_wallet_update_time ON ds_wallet(update_time);

-- 钱包交易流水表（含 V34 CHECK 约束）
CREATE TABLE IF NOT EXISTS ds_wallet_transaction (
    id              SERIAL          PRIMARY KEY,
    transaction_no  VARCHAR(64)     NOT NULL UNIQUE,
    wallet_no       VARCHAR(64)     NOT NULL,
    customer_no     VARCHAR(64)     NOT NULL,
    transaction_type VARCHAR(30)    NOT NULL,
    amount          BIGINT          NOT NULL,
    before_balance  BIGINT          NOT NULL,
    after_balance   BIGINT          NOT NULL,
    description     VARCHAR(200)    NOT NULL,
    reference_no    VARCHAR(64),
    reference_type  VARCHAR(30),
    status          VARCHAR(20)     NOT NULL DEFAULT 'SUCCESS',
    remark          TEXT,
    create_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_transaction_amount_nonzero CHECK (amount != 0)
);

COMMENT ON TABLE ds_wallet_transaction IS '钱包交易流水表，记录所有资金变动';
COMMENT ON COLUMN ds_wallet_transaction.id IS '主键ID';
COMMENT ON COLUMN ds_wallet_transaction.transaction_no IS '交易流水号，唯一标识';
COMMENT ON COLUMN ds_wallet_transaction.wallet_no IS '钱包编号';
COMMENT ON COLUMN ds_wallet_transaction.customer_no IS '用户编号';
COMMENT ON COLUMN ds_wallet_transaction.transaction_type IS '交易类型: RECHARGE(充值), WITHDRAW(提现), PAYMENT(支付), REFUND(退款), RED_PACKET_CONVERT(红包兑换), FEE(手续费), PENALTY(违约金)';
COMMENT ON COLUMN ds_wallet_transaction.amount IS '交易金额（分），正数表示收入，负数表示支出';
COMMENT ON COLUMN ds_wallet_transaction.before_balance IS '交易前余额（分）';
COMMENT ON COLUMN ds_wallet_transaction.after_balance IS '交易后余额（分）';
COMMENT ON COLUMN ds_wallet_transaction.description IS '交易描述';
COMMENT ON COLUMN ds_wallet_transaction.reference_no IS '关联单号，如订单号、红包编号等';
COMMENT ON COLUMN ds_wallet_transaction.reference_type IS '关联类型: ORDER(订单), RED_PACKET(红包), COUPON(优惠券)等';
COMMENT ON COLUMN ds_wallet_transaction.status IS '交易状态: SUCCESS(成功), FAILED(失败), PROCESSING(处理中)';
COMMENT ON COLUMN ds_wallet_transaction.remark IS '备注信息';
COMMENT ON COLUMN ds_wallet_transaction.create_time IS '创建时间';

CREATE INDEX IF NOT EXISTS idx_transaction_wallet_no ON ds_wallet_transaction(wallet_no);
CREATE INDEX IF NOT EXISTS idx_transaction_customer_no ON ds_wallet_transaction(customer_no);
CREATE INDEX IF NOT EXISTS idx_transaction_type ON ds_wallet_transaction(transaction_type);
CREATE INDEX IF NOT EXISTS idx_transaction_reference ON ds_wallet_transaction(reference_no, reference_type);
CREATE INDEX IF NOT EXISTS idx_transaction_create_time ON ds_wallet_transaction(create_time);
CREATE INDEX IF NOT EXISTS idx_transaction_status ON ds_wallet_transaction(status);

-- 充值订单表（含 V32 plan_no 字段）
CREATE TABLE IF NOT EXISTS ds_payment_order (
    id                      SERIAL          PRIMARY KEY,
    order_no                VARCHAR(64)     NOT NULL UNIQUE,
    customer_no             VARCHAR(64)     NOT NULL,
    wallet_no               VARCHAR(64)     NOT NULL,
    amount                  BIGINT          NOT NULL,
    platform                VARCHAR(20)     NOT NULL,
    payment_method          VARCHAR(30)     NOT NULL,
    third_party_order_no    VARCHAR(128),
    third_party_transaction_no VARCHAR(128),
    payment_params          TEXT,
    callback_data           TEXT,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    paid_time               TIMESTAMP,
    expire_time             TIMESTAMP       NOT NULL,
    notify_url              VARCHAR(500),
    return_url              VARCHAR(500),
    remark                  TEXT,
    plan_no                 VARCHAR(64),
    create_time             TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_payment_order IS '充值订单表，记录充值订单信息和支付状态';
COMMENT ON COLUMN ds_payment_order.id IS '主键ID';
COMMENT ON COLUMN ds_payment_order.order_no IS '订单号，唯一标识';
COMMENT ON COLUMN ds_payment_order.customer_no IS '用户编号';
COMMENT ON COLUMN ds_payment_order.wallet_no IS '钱包编号';
COMMENT ON COLUMN ds_payment_order.amount IS '充值金额（分）';
COMMENT ON COLUMN ds_payment_order.platform IS '支付平台: WECHAT(微信), ALIPAY(支付宝), DOUYIN(抖音)';
COMMENT ON COLUMN ds_payment_order.payment_method IS '支付方式: WECHAT_MINI(微信小程序), ALIPAY_MINI(支付宝小程序), DOUYIN_MINI(抖音小程序)';
COMMENT ON COLUMN ds_payment_order.third_party_order_no IS '第三方订单号';
COMMENT ON COLUMN ds_payment_order.third_party_transaction_no IS '第三方交易号';
COMMENT ON COLUMN ds_payment_order.payment_params IS '支付参数，JSON格式';
COMMENT ON COLUMN ds_payment_order.callback_data IS '回调数据，JSON格式';
COMMENT ON COLUMN ds_payment_order.status IS '订单状态: PENDING(待支付), PAID(已支付), CANCELLED(已取消), EXPIRED(已过期), REFUNDED(已退款)';
COMMENT ON COLUMN ds_payment_order.paid_time IS '支付完成时间';
COMMENT ON COLUMN ds_payment_order.expire_time IS '订单过期时间';
COMMENT ON COLUMN ds_payment_order.notify_url IS '支付结果通知地址';
COMMENT ON COLUMN ds_payment_order.return_url IS '支付完成跳转地址';
COMMENT ON COLUMN ds_payment_order.remark IS '备注信息';
COMMENT ON COLUMN ds_payment_order.plan_no IS '关联订单计划编号，对应 ds_order_plan.plan_no';
COMMENT ON COLUMN ds_payment_order.create_time IS '创建时间';
COMMENT ON COLUMN ds_payment_order.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_payment_customer_no ON ds_payment_order(customer_no);
CREATE INDEX IF NOT EXISTS idx_payment_wallet_no ON ds_payment_order(wallet_no);
CREATE INDEX IF NOT EXISTS idx_payment_platform ON ds_payment_order(platform);
CREATE INDEX IF NOT EXISTS idx_payment_status ON ds_payment_order(status);
CREATE INDEX IF NOT EXISTS idx_payment_third_party_order ON ds_payment_order(third_party_order_no);
CREATE INDEX IF NOT EXISTS idx_payment_create_time ON ds_payment_order(create_time);
CREATE INDEX IF NOT EXISTS idx_payment_expire_time ON ds_payment_order(expire_time);
CREATE INDEX IF NOT EXISTS idx_payment_order_plan_no ON ds_payment_order(plan_no);

-- 提现申请表
CREATE TABLE IF NOT EXISTS ds_withdraw_request (
    id                  SERIAL          PRIMARY KEY,
    request_no          VARCHAR(64)     NOT NULL UNIQUE,
    customer_no         VARCHAR(64)     NOT NULL,
    wallet_no           VARCHAR(64)     NOT NULL,
    amount              BIGINT          NOT NULL,
    withdraw_type       VARCHAR(20)     NOT NULL,
    account_type        VARCHAR(30)     NOT NULL,
    account_name        VARCHAR(100)    NOT NULL,
    account_number      VARCHAR(100)    NOT NULL,
    bank_name           VARCHAR(100),
    bank_branch         VARCHAR(200),
    fee_amount          BIGINT          NOT NULL DEFAULT 0,
    actual_amount       BIGINT          NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    audit_user          VARCHAR(64),
    audit_time          TIMESTAMP,
    audit_remark        TEXT,
    process_time        TIMESTAMP,
    process_result      TEXT,
    third_party_order_no VARCHAR(128),
    remark              TEXT,
    create_time         TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_withdraw_request IS '提现申请表，记录用户提现申请和处理状态';
COMMENT ON COLUMN ds_withdraw_request.id IS '主键ID';
COMMENT ON COLUMN ds_withdraw_request.request_no IS '提现申请号，唯一标识';
COMMENT ON COLUMN ds_withdraw_request.customer_no IS '用户编号';
COMMENT ON COLUMN ds_withdraw_request.wallet_no IS '钱包编号';
COMMENT ON COLUMN ds_withdraw_request.amount IS '提现金额（分）';
COMMENT ON COLUMN ds_withdraw_request.withdraw_type IS '提现类型: ALIPAY(支付宝), BANK_CARD(银行卡)';
COMMENT ON COLUMN ds_withdraw_request.account_type IS '账户类型: PERSONAL(个人), COMPANY(企业)';
COMMENT ON COLUMN ds_withdraw_request.account_name IS '账户姓名/企业名称';
COMMENT ON COLUMN ds_withdraw_request.account_number IS '账户号码';
COMMENT ON COLUMN ds_withdraw_request.bank_name IS '银行名称（银行卡提现时必填）';
COMMENT ON COLUMN ds_withdraw_request.bank_branch IS '开户行支行（银行卡提现时选填）';
COMMENT ON COLUMN ds_withdraw_request.fee_amount IS '手续费金额（分）';
COMMENT ON COLUMN ds_withdraw_request.actual_amount IS '实际到账金额（分）';
COMMENT ON COLUMN ds_withdraw_request.status IS '申请状态: PENDING(待审核), APPROVED(已审核), PROCESSING(处理中), SUCCESS(成功), FAILED(失败), CANCELLED(已取消)';
COMMENT ON COLUMN ds_withdraw_request.audit_user IS '审核人';
COMMENT ON COLUMN ds_withdraw_request.audit_time IS '审核时间';
COMMENT ON COLUMN ds_withdraw_request.audit_remark IS '审核备注';
COMMENT ON COLUMN ds_withdraw_request.process_time IS '处理完成时间';
COMMENT ON COLUMN ds_withdraw_request.process_result IS '处理结果';
COMMENT ON COLUMN ds_withdraw_request.third_party_order_no IS '第三方订单号';
COMMENT ON COLUMN ds_withdraw_request.remark IS '备注信息';
COMMENT ON COLUMN ds_withdraw_request.create_time IS '创建时间';
COMMENT ON COLUMN ds_withdraw_request.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_withdraw_customer_no ON ds_withdraw_request(customer_no);
CREATE INDEX IF NOT EXISTS idx_withdraw_wallet_no ON ds_withdraw_request(wallet_no);
CREATE INDEX IF NOT EXISTS idx_withdraw_status ON ds_withdraw_request(status);
CREATE INDEX IF NOT EXISTS idx_withdraw_create_time ON ds_withdraw_request(create_time);
CREATE INDEX IF NOT EXISTS idx_withdraw_audit_time ON ds_withdraw_request(audit_time);

-- 支付回调日志表
CREATE TABLE IF NOT EXISTS ds_payment_callback_log (
    id              SERIAL          PRIMARY KEY,
    order_no        VARCHAR(64)     NOT NULL,
    platform        VARCHAR(20)     NOT NULL,
    callback_type   VARCHAR(30)     NOT NULL,
    callback_data   TEXT            NOT NULL,
    signature       VARCHAR(500),
    verify_result   BOOLEAN,
    process_result  VARCHAR(20),
    error_message   TEXT,
    create_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_payment_callback_log IS '支付回调日志表，记录第三方支付平台回调信息';
COMMENT ON COLUMN ds_payment_callback_log.id IS '主键ID';
COMMENT ON COLUMN ds_payment_callback_log.order_no IS '订单号';
COMMENT ON COLUMN ds_payment_callback_log.platform IS '支付平台';
COMMENT ON COLUMN ds_payment_callback_log.callback_type IS '回调类型: PAYMENT(支付回调), REFUND(退款回调)';
COMMENT ON COLUMN ds_payment_callback_log.callback_data IS '回调数据，原始JSON';
COMMENT ON COLUMN ds_payment_callback_log.signature IS '签名信息';
COMMENT ON COLUMN ds_payment_callback_log.verify_result IS '签名验证结果';
COMMENT ON COLUMN ds_payment_callback_log.process_result IS '处理结果: SUCCESS(成功), FAILED(失败), IGNORED(忽略)';
COMMENT ON COLUMN ds_payment_callback_log.error_message IS '错误信息';
COMMENT ON COLUMN ds_payment_callback_log.create_time IS '创建时间';

CREATE INDEX IF NOT EXISTS idx_callback_order_no ON ds_payment_callback_log(order_no);
CREATE INDEX IF NOT EXISTS idx_callback_platform ON ds_payment_callback_log(platform);
CREATE INDEX IF NOT EXISTS idx_callback_create_time ON ds_payment_callback_log(create_time);

-- 支付风控规则表 (V14)
CREATE TABLE IF NOT EXISTS ds_payment_risk_rule (
    id          BIGSERIAL       PRIMARY KEY,
    rule_code   VARCHAR(64)     NOT NULL UNIQUE,
    rule_name   VARCHAR(100)    NOT NULL,
    rule_type   VARCHAR(20)     NOT NULL,
    threshold   BIGINT          NOT NULL DEFAULT 0,
    time_window INT             NOT NULL DEFAULT 0,
    action      VARCHAR(20)     NOT NULL DEFAULT 'REJECT',
    is_enable   BOOLEAN         NOT NULL DEFAULT TRUE,
    create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_risk_rule_type ON ds_payment_risk_rule(rule_type);
CREATE INDEX IF NOT EXISTS idx_risk_rule_enable ON ds_payment_risk_rule(is_enable);

-- 支付事件表 (V14)
CREATE TABLE IF NOT EXISTS ds_payment_event (
    id              BIGSERIAL       PRIMARY KEY,
    event_no        VARCHAR(64)     NOT NULL UNIQUE,
    order_no        VARCHAR(64),
    event_type      VARCHAR(30)     NOT NULL,
    channel         VARCHAR(20)     NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    request_content TEXT,
    response_content TEXT,
    create_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_event_order_no ON ds_payment_event(order_no);
CREATE INDEX IF NOT EXISTS idx_event_type ON ds_payment_event(event_type);
CREATE INDEX IF NOT EXISTS idx_event_channel ON ds_payment_event(channel);
CREATE INDEX IF NOT EXISTS idx_event_create_time ON ds_payment_event(create_time);

-- 对账任务表 (V14)
CREATE TABLE IF NOT EXISTS ds_payment_reconciliation_task (
    id              BIGSERIAL       PRIMARY KEY,
    date            VARCHAR(10)     NOT NULL,
    channel         VARCHAR(20)     NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    total_orders    INT             NOT NULL DEFAULT 0,
    total_amount    BIGINT          NOT NULL DEFAULT 0,
    match_count     INT             NOT NULL DEFAULT 0,
    mismatch_count  INT             NOT NULL DEFAULT 0,
    create_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(date, channel)
);

CREATE INDEX IF NOT EXISTS idx_recon_date ON ds_payment_reconciliation_task(date);
CREATE INDEX IF NOT EXISTS idx_recon_channel ON ds_payment_reconciliation_task(channel);
CREATE INDEX IF NOT EXISTS idx_recon_status ON ds_payment_reconciliation_task(status);

-- 退款记录表（含 V23 增强字段）
CREATE TABLE IF NOT EXISTS ds_refund (
    id                  BIGSERIAL       PRIMARY KEY,
    refund_no           VARCHAR(64)     NOT NULL UNIQUE,
    order_no            VARCHAR(64)     NOT NULL,
    amount              BIGINT          NOT NULL,
    reason              VARCHAR(500),
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    third_party_refund_no VARCHAR(128),
    audit_user          VARCHAR(64),
    audit_time          TIMESTAMP,
    audit_remark        VARCHAR(500),
    refund_policy       VARCHAR(32),
    request_source      VARCHAR(32),
    applicant_id        VARCHAR(64),
    channel_status      VARCHAR(32),
    create_time         TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_refund_order_no ON ds_refund(order_no);
CREATE INDEX IF NOT EXISTS idx_refund_status ON ds_refund(status);
CREATE INDEX IF NOT EXISTS idx_refund_create_time ON ds_refund(create_time);
CREATE INDEX IF NOT EXISTS idx_refund_policy ON ds_refund(refund_policy);
CREATE INDEX IF NOT EXISTS idx_refund_request_source ON ds_refund(request_source);

-- 支付渠道配置表 (V15)
CREATE TABLE IF NOT EXISTS ds_payment_channel_config (
    id          BIGSERIAL       PRIMARY KEY,
    channel_name VARCHAR(50)     NOT NULL,
    channel_code VARCHAR(20)     NOT NULL UNIQUE,
    app_id      VARCHAR(100),
    merchant_id VARCHAR(100),
    public_key  TEXT,
    private_key TEXT,
    notify_url  VARCHAR(255),
    is_enable   BOOLEAN         NOT NULL DEFAULT TRUE,
    create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_config_channel_code ON ds_payment_channel_config(channel_code);

-- 钱包余额一致性校验日志表 (V44)
CREATE TABLE IF NOT EXISTS ds_wallet_integrity_log (
    id                BIGSERIAL       PRIMARY KEY,
    wallet_no         VARCHAR(64)     NOT NULL,
    customer_no       VARCHAR(64)     NOT NULL,
    expected_balance  BIGINT          NOT NULL,
    actual_balance    BIGINT          NOT NULL,
    expected_frozen   BIGINT          NOT NULL,
    actual_frozen     BIGINT          NOT NULL,
    hash_valid        BOOLEAN,
    status            VARCHAR(20)     NOT NULL DEFAULT 'DETECTED',
    detail            TEXT,
    create_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_integrity_wallet_no ON ds_wallet_integrity_log(wallet_no);
CREATE INDEX IF NOT EXISTS idx_integrity_status ON ds_wallet_integrity_log(status);
CREATE INDEX IF NOT EXISTS idx_integrity_create_time ON ds_wallet_integrity_log(create_time);

COMMENT ON TABLE ds_wallet_integrity_log IS '钱包余额一致性校验日志表';
COMMENT ON COLUMN ds_wallet_integrity_log.wallet_no IS '钱包编号';
COMMENT ON COLUMN ds_wallet_integrity_log.customer_no IS '用户编号';
COMMENT ON COLUMN ds_wallet_integrity_log.expected_balance IS '期望可用余额（分），基于流水计算';
COMMENT ON COLUMN ds_wallet_integrity_log.actual_balance IS '实际可用余额（分），数据库中的值';
COMMENT ON COLUMN ds_wallet_integrity_log.expected_frozen IS '期望冻结余额（分）';
COMMENT ON COLUMN ds_wallet_integrity_log.actual_frozen IS '实际冻结余额（分）';
COMMENT ON COLUMN ds_wallet_integrity_log.hash_valid IS '哈希校验是否通过';
COMMENT ON COLUMN ds_wallet_integrity_log.status IS '状态: DETECTED(已检测), RECONCILED(已修复), IGNORED(已忽略)';
COMMENT ON COLUMN ds_wallet_integrity_log.detail IS '详细信息';
COMMENT ON COLUMN ds_wallet_integrity_log.create_time IS '创建时间';

-- ============================================================
-- 飞手联盟模块（V2.1.0）
-- ============================================================

-- 联盟加盟记录表（含 V2 deposit_type/payscore/exit 字段）
CREATE TABLE IF NOT EXISTS ds_alliance_membership (
    id                BIGSERIAL       PRIMARY KEY,
    membership_no     VARCHAR(32)     NOT NULL UNIQUE,
    provider_no       VARCHAR(32)     NOT NULL,
    fee_amount        NUMERIC(10, 2)  NOT NULL,
    status            VARCHAR(20)     DEFAULT 'PENDING' NOT NULL,
    payment_order_no  VARCHAR(64),
    paid_time         TIMESTAMP,
    cancel_time       TIMESTAMP,
    cancel_reason     VARCHAR(200),
    create_time       TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time       TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deposit_type      VARCHAR(20),
    payscore_order_no VARCHAR(64),
    exit_time         TIMESTAMP,
    exit_reason       VARCHAR(200)
);

COMMENT ON TABLE ds_alliance_membership IS '联盟加盟记录表';
COMMENT ON COLUMN ds_alliance_membership.id IS '主键';
COMMENT ON COLUMN ds_alliance_membership.membership_no IS '加盟业务编号（AM前缀）';
COMMENT ON COLUMN ds_alliance_membership.provider_no IS '关联飞手编号';
COMMENT ON COLUMN ds_alliance_membership.fee_amount IS '加盟费金额（元）';
COMMENT ON COLUMN ds_alliance_membership.status IS '加盟状态：PENDING-待支付，PAID-已支付，CANCELLED-已取消';
COMMENT ON COLUMN ds_alliance_membership.payment_order_no IS '支付中台支付订单号';
COMMENT ON COLUMN ds_alliance_membership.paid_time IS '缴费成功时间';
COMMENT ON COLUMN ds_alliance_membership.cancel_time IS '取消时间';
COMMENT ON COLUMN ds_alliance_membership.cancel_reason IS '取消原因';
COMMENT ON COLUMN ds_alliance_membership.create_time IS '创建时间';
COMMENT ON COLUMN ds_alliance_membership.update_time IS '更新时间';
COMMENT ON COLUMN ds_alliance_membership.deposit_type IS '缴纳方式：AMOUNT-直接支付，WECHAT_PAYSCORE-微信支付分，ALIPAY_ZHIMA-支付宝芝麻信用';
COMMENT ON COLUMN ds_alliance_membership.payscore_order_no IS '支付分服务订单号（免押方式）';
COMMENT ON COLUMN ds_alliance_membership.exit_time IS '退出时间';
COMMENT ON COLUMN ds_alliance_membership.exit_reason IS '退出原因';

CREATE INDEX IF NOT EXISTS idx_alliance_membership_provider_no ON ds_alliance_membership(provider_no);
CREATE INDEX IF NOT EXISTS idx_alliance_membership_status ON ds_alliance_membership(status);

-- 渠道信息表（V2.1.0 V1，plan_no 已在 V4 中重命名为 order_no）
CREATE TABLE IF NOT EXISTS ds_order_channel (
    id                  BIGSERIAL       PRIMARY KEY,
    channel_no          VARCHAR(32)     NOT NULL UNIQUE,
    order_no            VARCHAR(32)     NOT NULL,
    channel_type        VARCHAR(30)     NOT NULL,
    channel_owner_type  SMALLINT        NOT NULL,
    channel_name        VARCHAR(100),
    channel_contact     VARCHAR(50),
    create_time         TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_order_channel IS '渠道信息表';
COMMENT ON COLUMN ds_order_channel.id IS '主键';
COMMENT ON COLUMN ds_order_channel.channel_no IS '渠道业务编号（CH前缀）';
COMMENT ON COLUMN ds_order_channel.order_no IS '关联订单编号';
COMMENT ON COLUMN ds_order_channel.channel_type IS '渠道来源：WECHAT_MINI_PROGRAM/WECHAT_GROUP/DOUYIN/ALIPAY/OFFLINE/PLATFORM/OTHER';
COMMENT ON COLUMN ds_order_channel.channel_owner_type IS '渠道所有者类型：1-个人，2-企业';
COMMENT ON COLUMN ds_order_channel.channel_name IS '渠道名称/备注';
COMMENT ON COLUMN ds_order_channel.channel_contact IS '主要联系人';
COMMENT ON COLUMN ds_order_channel.create_time IS '创建时间';
COMMENT ON COLUMN ds_order_channel.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_order_channel_order_no ON ds_order_channel(order_no);
CREATE INDEX IF NOT EXISTS idx_order_channel_type ON ds_order_channel(channel_type);

-- 渠道属性表（V2.1.0 V1）
CREATE TABLE IF NOT EXISTS ds_order_channel_attr (
    id          BIGSERIAL       PRIMARY KEY,
    channel_no  VARCHAR(32)     NOT NULL,
    attr_key    VARCHAR(50)     NOT NULL,
    attr_value  TEXT,
    attr_type   VARCHAR(20)     NOT NULL DEFAULT 'STRING',
    create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_order_channel_attr IS '渠道属性表';
COMMENT ON COLUMN ds_order_channel_attr.id IS '主键';
COMMENT ON COLUMN ds_order_channel_attr.channel_no IS '关联渠道编号';
COMMENT ON COLUMN ds_order_channel_attr.attr_key IS '属性键';
COMMENT ON COLUMN ds_order_channel_attr.attr_value IS '属性值';
COMMENT ON COLUMN ds_order_channel_attr.attr_type IS '属性类型：STRING/NUMBER/URL';
COMMENT ON COLUMN ds_order_channel_attr.create_time IS '创建时间';
COMMENT ON COLUMN ds_order_channel_attr.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_order_channel_attr_channel_no ON ds_order_channel_attr(channel_no);

-- 协议确认表（V2.1.0 V1，plan_no 已在 V4 中重命名为 order_no）
CREATE TABLE IF NOT EXISTS ds_order_agreement (
    id                BIGSERIAL       PRIMARY KEY,
    order_no          VARCHAR(32)     NOT NULL,
    agreement_type    VARCHAR(30)     NOT NULL,
    agreement_version VARCHAR(16)     NOT NULL DEFAULT '1.0',
    agreed            BOOLEAN         NOT NULL DEFAULT FALSE,
    agreed_time       TIMESTAMP,
    create_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_order_agreement IS '协议确认表';
COMMENT ON COLUMN ds_order_agreement.id IS '主键';
COMMENT ON COLUMN ds_order_agreement.order_no IS '关联订单编号';
COMMENT ON COLUMN ds_order_agreement.agreement_type IS '协议类型：ORDER_COMMITMENT-真实订单承诺协议';
COMMENT ON COLUMN ds_order_agreement.agreement_version IS '协议版本号';
COMMENT ON COLUMN ds_order_agreement.agreed IS '是否已同意';
COMMENT ON COLUMN ds_order_agreement.agreed_time IS '同意时间';
COMMENT ON COLUMN ds_order_agreement.create_time IS '创建时间';
COMMENT ON COLUMN ds_order_agreement.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_order_agreement_order_no ON ds_order_agreement(order_no);

-- 订单属性表（V2.1.0 V1，plan_no 已在 V4 中重命名为 order_no）
CREATE TABLE IF NOT EXISTS ds_order_attribute (
    id          BIGSERIAL       PRIMARY KEY,
    order_no    VARCHAR(32)     NOT NULL,
    attr_key    VARCHAR(50)     NOT NULL,
    attr_value  TEXT,
    attr_type   VARCHAR(20)     NOT NULL DEFAULT 'STRING',
    attr_label  VARCHAR(50),
    create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_order_attribute IS '订单属性表';
COMMENT ON COLUMN ds_order_attribute.id IS '主键';
COMMENT ON COLUMN ds_order_attribute.order_no IS '关联订单编号';
COMMENT ON COLUMN ds_order_attribute.attr_key IS '属性键';
COMMENT ON COLUMN ds_order_attribute.attr_value IS '属性值';
COMMENT ON COLUMN ds_order_attribute.attr_type IS '属性类型：BOOLEAN/STRING/NUMBER';
COMMENT ON COLUMN ds_order_attribute.attr_label IS '属性中文标签';
COMMENT ON COLUMN ds_order_attribute.create_time IS '创建时间';
COMMENT ON COLUMN ds_order_attribute.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_order_attribute_order_no ON ds_order_attribute(order_no);

-- 保证金表（V2.1.0 V1 + V9/V52 payscore 字段，plan_no 已在 V4 中重命名为 order_no）
CREATE TABLE IF NOT EXISTS ds_order_deposit (
    id                  BIGSERIAL       PRIMARY KEY,
    deposit_no          VARCHAR(32)     NOT NULL UNIQUE,
    order_no            VARCHAR(32)     NOT NULL,
    deposit_role        VARCHAR(20)     NOT NULL,
    user_id             VARCHAR(32)     NOT NULL,
    deposit_type        VARCHAR(20)     NOT NULL,
    amount              DECIMAL(10, 2),
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    payment_order_no    VARCHAR(64),
    paid_time           TIMESTAMP,
    refund_time         TIMESTAMP,
    refund_reason       VARCHAR(200),
    payscore_order_no   VARCHAR(64),
    payscore_platform   VARCHAR(20),
    create_time         TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_order_deposit IS '保证金表';
COMMENT ON COLUMN ds_order_deposit.id IS '主键';
COMMENT ON COLUMN ds_order_deposit.deposit_no IS '保证金业务编号（OD前缀）';
COMMENT ON COLUMN ds_order_deposit.order_no IS '关联订单编号';
COMMENT ON COLUMN ds_order_deposit.deposit_role IS '保证金角色：ORDER-发单方，PILOT-接单方';
COMMENT ON COLUMN ds_order_deposit.user_id IS '缴纳用户ID';
COMMENT ON COLUMN ds_order_deposit.deposit_type IS '缴纳方式：AMOUNT-实际金额，WECHAT_PAYSCORE-微信支付分，ALIPAY_ZHIMA-支付宝芝麻信用';
COMMENT ON COLUMN ds_order_deposit.amount IS '保证金金额（元）；信用分方式时为授权额度';
COMMENT ON COLUMN ds_order_deposit.status IS '保证金状态：PENDING-待缴纳，PAID-已缴纳，AUTHORIZED-信用分已授权，REFUNDED-已退还，FORFEITED-已扣除';
COMMENT ON COLUMN ds_order_deposit.payment_order_no IS '支付订单号（金额方式）';
COMMENT ON COLUMN ds_order_deposit.paid_time IS '缴纳/授权时间';
COMMENT ON COLUMN ds_order_deposit.refund_time IS '退还时间';
COMMENT ON COLUMN ds_order_deposit.refund_reason IS '退还原因';
COMMENT ON COLUMN ds_order_deposit.payscore_order_no IS '支付分服务订单号（信用免押方式）';
COMMENT ON COLUMN ds_order_deposit.payscore_platform IS '支付分平台：WECHAT_PAYSCORE / ALIPAY_ZHIMA';
COMMENT ON COLUMN ds_order_deposit.create_time IS '创建时间';
COMMENT ON COLUMN ds_order_deposit.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_order_deposit_order_no ON ds_order_deposit(order_no);
CREATE INDEX IF NOT EXISTS idx_order_deposit_user_id ON ds_order_deposit(user_id);
CREATE INDEX IF NOT EXISTS idx_order_deposit_payscore_order_no ON ds_order_deposit(payscore_order_no);

-- 序列号表（V2.1.0 V8）
CREATE TABLE IF NOT EXISTS ds_sequence (
    name      VARCHAR(128)    PRIMARY KEY,
    next_val  BIGINT          NOT NULL DEFAULT 1
);

COMMENT ON TABLE ds_sequence IS '序列号表，持久化自增序列，应用重启后不会重复';
COMMENT ON COLUMN ds_sequence.name IS '序列名称（如 PO_260715）';
COMMENT ON COLUMN ds_sequence.next_val IS '下一个可用值';

-- ============================================================
-- 系统表
-- ============================================================

-- 系统审计日志表
CREATE TABLE IF NOT EXISTS ds_audit_log (
    id             BIGSERIAL PRIMARY KEY,
    username       VARCHAR(100),
    module         VARCHAR(100),
    operation      VARCHAR(200),
    method         VARCHAR(200),
    params         TEXT,
    result         TEXT,
    execution_time BIGINT,
    status         INTEGER,
    error_msg      TEXT,
    ip             VARCHAR(50),
    user_agent     VARCHAR(500),
    create_time    TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,
    user_id        VARCHAR(100)
);

COMMENT ON TABLE ds_audit_log IS '系统审计日志表';
COMMENT ON COLUMN ds_audit_log.id IS '日志唯一标识（自增主键）';
COMMENT ON COLUMN ds_audit_log.username IS '操作用户';
COMMENT ON COLUMN ds_audit_log.module IS '业务模块';
COMMENT ON COLUMN ds_audit_log.operation IS '操作类型/描述';
COMMENT ON COLUMN ds_audit_log.method IS '请求方法';
COMMENT ON COLUMN ds_audit_log.params IS '请求参数';
COMMENT ON COLUMN ds_audit_log.result IS '响应结果';
COMMENT ON COLUMN ds_audit_log.execution_time IS '执行时长 (毫秒)';
COMMENT ON COLUMN ds_audit_log.status IS '操作状态 (0-失败, 1-成功)';
COMMENT ON COLUMN ds_audit_log.error_msg IS '错误消息';
COMMENT ON COLUMN ds_audit_log.ip IS '操作IP地址';
COMMENT ON COLUMN ds_audit_log.user_agent IS '浏览器用户代理';
COMMENT ON COLUMN ds_audit_log.create_time IS '创建时间';
COMMENT ON COLUMN ds_audit_log.user_id IS '操作用户ID';

-- 系统消息通知表
CREATE TABLE IF NOT EXISTS ds_notification (
    id              BIGSERIAL PRIMARY KEY,
    notification_no VARCHAR(64)      NOT NULL,
    sys_user_id     VARCHAR(64)      NOT NULL,
    notify_type     SMALLINT         NOT NULL,
    content         VARCHAR(512)     NOT NULL,
    is_read         SMALLINT         DEFAULT 0,
    related_id      INTEGER,
    related_type    SMALLINT,
    create_time     TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_time       TIMESTAMP,
    update_time     TIMESTAMP        DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_notification IS '系统消息通知表';
COMMENT ON COLUMN ds_notification.id IS '通知唯一标识（自增主键）';
COMMENT ON COLUMN ds_notification.sys_user_id IS '接收用户ID（外键关联user表）';
COMMENT ON COLUMN ds_notification.notify_type IS '通知类型（1-订单通知，2-行程通知，3-系统通知）';
COMMENT ON COLUMN ds_notification.content IS '通知内容';
COMMENT ON COLUMN ds_notification.is_read IS '是否已读（0-未读，1-已读）';
COMMENT ON COLUMN ds_notification.related_id IS '关联业务主键ID（订单或行程自增主键）';
COMMENT ON COLUMN ds_notification.related_type IS '关联类型（1-订单，2-行程）';
COMMENT ON COLUMN ds_notification.create_time IS '通知生成时间';
COMMENT ON COLUMN ds_notification.read_time IS '用户阅读时间';

-- 评价表
CREATE TABLE IF NOT EXISTS ds_evaluation (
    id            BIGSERIAL PRIMARY KEY,
    evaluation_no VARCHAR(32)       NOT NULL UNIQUE,
    order_id      INTEGER           NOT NULL,
    order_no      VARCHAR(32)       NOT NULL,
    user_id       VARCHAR(64)       NOT NULL,
    provider_id   VARCHAR(64)       NOT NULL,
    score         SMALLINT          NOT NULL,
    content       TEXT,
    tags          JSONB,
    images        JSONB,
    anonymous     SMALLINT          DEFAULT 0,
    reply_content TEXT,
    reply_time    TIMESTAMP,
    create_time   TIMESTAMP         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP         NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_evaluation IS '评价表';
COMMENT ON COLUMN ds_evaluation.id IS '主键ID';
COMMENT ON COLUMN ds_evaluation.evaluation_no IS '评价编号';
COMMENT ON COLUMN ds_evaluation.order_id IS '订单ID';
COMMENT ON COLUMN ds_evaluation.order_no IS '订单编号';
COMMENT ON COLUMN ds_evaluation.user_id IS '评价人ID（客户）';
COMMENT ON COLUMN ds_evaluation.provider_id IS '被评价人ID（飞手）';
COMMENT ON COLUMN ds_evaluation.score IS '评分1-5';
COMMENT ON COLUMN ds_evaluation.content IS '评价内容';
COMMENT ON COLUMN ds_evaluation.tags IS '评价标签';
COMMENT ON COLUMN ds_evaluation.images IS '评价图片';
COMMENT ON COLUMN ds_evaluation.anonymous IS '是否匿名：0-否，1-是';
COMMENT ON COLUMN ds_evaluation.reply_content IS '飞手回复';
COMMENT ON COLUMN ds_evaluation.reply_time IS '回复时间';
COMMENT ON COLUMN ds_evaluation.create_time IS '创建时间';
COMMENT ON COLUMN ds_evaluation.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_evaluation_no ON ds_evaluation (evaluation_no);
CREATE INDEX IF NOT EXISTS idx_evaluation_order_no ON ds_evaluation (order_no);
CREATE INDEX IF NOT EXISTS idx_evaluation_user_id ON ds_evaluation (user_id);
CREATE INDEX IF NOT EXISTS idx_evaluation_provider_id ON ds_evaluation (provider_id);
CREATE INDEX IF NOT EXISTS idx_evaluation_create_time ON ds_evaluation (create_time);

-- 系统附件表（含 V36 storage_type）
CREATE TABLE IF NOT EXISTS ds_attachment (
    id           BIGSERIAL PRIMARY KEY,
    module       VARCHAR(50)      NOT NULL,
    file_name    VARCHAR(255)     NOT NULL,
    file_path    VARCHAR(500)     NOT NULL,
    file_size    BIGINT           NOT NULL,
    content_type VARCHAR(100)     NOT NULL,
    create_time  TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    storage_type VARCHAR(20)      DEFAULT 'minio'
);

COMMENT ON TABLE ds_attachment IS '系统附件表';
COMMENT ON COLUMN ds_attachment.module IS '所属模块';
COMMENT ON COLUMN ds_attachment.file_name IS '原始文件名';
COMMENT ON COLUMN ds_attachment.file_path IS 'MinIO ObjectName';
COMMENT ON COLUMN ds_attachment.file_size IS '文件大小';
COMMENT ON COLUMN ds_attachment.content_type IS 'MIME类型';
COMMENT ON COLUMN ds_attachment.create_time IS '上传时间';
COMMENT ON COLUMN ds_attachment.storage_type IS '存储类型标识：minio / aliyun';

-- 避坑指南表（V41 重建，is_deleted → deleted）
CREATE TABLE IF NOT EXISTS ds_pitfall_guide (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(255)     NOT NULL,
    summary     VARCHAR(500),
    content     TEXT,
    cover_url   VARCHAR(500),
    deleted     BOOLEAN          DEFAULT FALSE,
    create_time TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP        DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_pitfall_guide IS '避坑指南表';
COMMENT ON COLUMN ds_pitfall_guide.id IS '主键ID';
COMMENT ON COLUMN ds_pitfall_guide.title IS '标题';
COMMENT ON COLUMN ds_pitfall_guide.summary IS '摘要';
COMMENT ON COLUMN ds_pitfall_guide.content IS '内容（富文本）';
COMMENT ON COLUMN ds_pitfall_guide.cover_url IS '封面图URL';
COMMENT ON COLUMN ds_pitfall_guide.deleted IS '是否删除';
COMMENT ON COLUMN ds_pitfall_guide.create_time IS '创建时间';
COMMENT ON COLUMN ds_pitfall_guide.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_pitfall_guide_title ON ds_pitfall_guide (title);

-- ============================================================
-- 数据字典
-- ============================================================

-- 字典类型表
CREATE TABLE IF NOT EXISTS ds_dict_type (
    dict_id     BIGSERIAL PRIMARY KEY,
    dict_name   VARCHAR(100)     DEFAULT '' NOT NULL,
    dict_type   VARCHAR(100)     DEFAULT '' NOT NULL UNIQUE,
    status      CHAR             DEFAULT '0' NOT NULL,
    create_by   VARCHAR(64)      DEFAULT '',
    create_time TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,
    update_by   VARCHAR(64)      DEFAULT '',
    update_time TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,
    remark      VARCHAR(500)     DEFAULT ''
);

COMMENT ON TABLE ds_dict_type IS '字典类型表';
COMMENT ON COLUMN ds_dict_type.dict_id IS '字典主键';
COMMENT ON COLUMN ds_dict_type.dict_name IS '字典名称';
COMMENT ON COLUMN ds_dict_type.dict_type IS '字典类型';
COMMENT ON COLUMN ds_dict_type.status IS '状态（0正常 1停用）';
COMMENT ON COLUMN ds_dict_type.create_by IS '创建者';
COMMENT ON COLUMN ds_dict_type.create_time IS '创建时间';
COMMENT ON COLUMN ds_dict_type.update_by IS '更新者';
COMMENT ON COLUMN ds_dict_type.update_time IS '更新时间';
COMMENT ON COLUMN ds_dict_type.remark IS '备注';

CREATE INDEX IF NOT EXISTS idx_dict_type_dict_type ON ds_dict_type (dict_type);

-- 字典数据表
CREATE TABLE IF NOT EXISTS ds_dict_data (
    dict_code   BIGSERIAL PRIMARY KEY,
    dict_sort   INTEGER          DEFAULT 0,
    dict_label  VARCHAR(100)     DEFAULT '' NOT NULL,
    dict_value  VARCHAR(100)     DEFAULT '' NOT NULL,
    dict_type   VARCHAR(100)     DEFAULT '' NOT NULL,
    css_class   VARCHAR(100)     DEFAULT '',
    list_class  VARCHAR(100)     DEFAULT '',
    is_default  CHAR             DEFAULT 'N' NOT NULL,
    status      CHAR             DEFAULT '0' NOT NULL,
    create_by   VARCHAR(64)      DEFAULT '',
    create_time TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,
    update_by   VARCHAR(64)      DEFAULT '',
    update_time TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,
    remark      VARCHAR(500)     DEFAULT ''
);

COMMENT ON TABLE ds_dict_data IS '字典数据表';
COMMENT ON COLUMN ds_dict_data.dict_code IS '字典编码';
COMMENT ON COLUMN ds_dict_data.dict_sort IS '字典排序';
COMMENT ON COLUMN ds_dict_data.dict_label IS '字典标签';
COMMENT ON COLUMN ds_dict_data.dict_value IS '字典键值';
COMMENT ON COLUMN ds_dict_data.dict_type IS '字典类型';
COMMENT ON COLUMN ds_dict_data.css_class IS '样式属性（其他样式扩展）';
COMMENT ON COLUMN ds_dict_data.list_class IS '表格回显样式';
COMMENT ON COLUMN ds_dict_data.is_default IS '是否默认（Y是 N否）';
COMMENT ON COLUMN ds_dict_data.status IS '状态（0正常 1停用）';
COMMENT ON COLUMN ds_dict_data.create_by IS '创建者';
COMMENT ON COLUMN ds_dict_data.create_time IS '创建时间';
COMMENT ON COLUMN ds_dict_data.update_by IS '更新者';
COMMENT ON COLUMN ds_dict_data.update_time IS '更新时间';
COMMENT ON COLUMN ds_dict_data.remark IS '备注';

CREATE INDEX IF NOT EXISTS idx_dict_data_dict_sort ON ds_dict_data (dict_sort);
CREATE INDEX IF NOT EXISTS idx_dict_data_dict_type ON ds_dict_data (dict_type);

-- 区域表（省市区三级联动）
CREATE TABLE IF NOT EXISTS ds_dou_area (
    area_id   BIGSERIAL PRIMARY KEY,
    parent_id BIGINT            DEFAULT 0 NOT NULL,
    name      VARCHAR(120)      DEFAULT '' NOT NULL
);

COMMENT ON TABLE ds_dou_area IS '区域表（省市区三级联动）';
COMMENT ON COLUMN ds_dou_area.area_id IS '区域ID';
COMMENT ON COLUMN ds_dou_area.parent_id IS '父级区域ID（0表示省级）';
COMMENT ON COLUMN ds_dou_area.name IS '区域名称';

CREATE INDEX IF NOT EXISTS idx_ds_dou_area_parent_id ON ds_dou_area (parent_id);

-- 系统配置表
CREATE TABLE IF NOT EXISTS ds_system_config (
    id           BIGSERIAL PRIMARY KEY,
    config_group VARCHAR(64)      NOT NULL,
    config_key   VARCHAR(128)     NOT NULL,
    config_name  VARCHAR(128)     NOT NULL,
    config_value TEXT,
    value_type   VARCHAR(32)      DEFAULT 'STRING' NOT NULL,
    is_encrypted BOOLEAN          DEFAULT false NOT NULL,
    is_public    BOOLEAN          DEFAULT false NOT NULL,
    is_enabled   BOOLEAN          DEFAULT true NOT NULL,
    description  VARCHAR(500),
    sort_order   INTEGER          DEFAULT 0 NOT NULL,
    create_by    VARCHAR(64),
    update_by    VARCHAR(64),
    create_time  TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_system_config_group_key UNIQUE (config_group, config_key)
);

CREATE INDEX IF NOT EXISTS idx_system_config_enabled ON ds_system_config (is_enabled);
CREATE INDEX IF NOT EXISTS idx_system_config_group ON ds_system_config (config_group);
CREATE INDEX IF NOT EXISTS idx_system_config_public ON ds_system_config (is_public);

-- ============================================================
-- 消息系统
-- ============================================================

-- 站内消息（消息体）
CREATE TABLE IF NOT EXISTS ds_message (
    id           BIGSERIAL PRIMARY KEY,
    message_no   VARCHAR(64)      NOT NULL UNIQUE,
    title        VARCHAR(255)     NOT NULL,
    content      TEXT             NOT NULL,
    message_type VARCHAR(32)      NOT NULL,
    payload      TEXT,
    sender_id    VARCHAR(64),
    create_time  TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP        DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_message IS '站内消息（消息体）';
COMMENT ON COLUMN ds_message.id IS '主键ID';
COMMENT ON COLUMN ds_message.message_no IS '消息业务编号';
COMMENT ON COLUMN ds_message.title IS '消息标题';
COMMENT ON COLUMN ds_message.content IS '消息内容';
COMMENT ON COLUMN ds_message.message_type IS '消息类型（ALERT/INFO/MESSAGE/TODO等）';
COMMENT ON COLUMN ds_message.payload IS '扩展负载（JSON字符串）';
COMMENT ON COLUMN ds_message.sender_id IS '发送人ID（系统消息可为空）';
COMMENT ON COLUMN ds_message.create_time IS '创建时间';
COMMENT ON COLUMN ds_message.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_message_ctime ON ds_message (create_time);
CREATE INDEX IF NOT EXISTS idx_message_type ON ds_message (message_type);

-- 用户消息收件箱
CREATE TABLE IF NOT EXISTS ds_user_message (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(64)      NOT NULL,
    message_no  VARCHAR(64)      NOT NULL,
    status      VARCHAR(16)      DEFAULT 'UNREAD' NOT NULL,
    read_time   TIMESTAMP,
    create_time TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_user_message UNIQUE (user_id, message_no)
);

COMMENT ON TABLE ds_user_message IS '用户消息收件箱';
COMMENT ON COLUMN ds_user_message.id IS '主键ID';
COMMENT ON COLUMN ds_user_message.user_id IS '用户ID';
COMMENT ON COLUMN ds_user_message.message_no IS '消息业务编号（关联 ds_message.message_no）';
COMMENT ON COLUMN ds_user_message.status IS '状态（UNREAD/READ/DONE）';
COMMENT ON COLUMN ds_user_message.read_time IS '阅读/处理时间';
COMMENT ON COLUMN ds_user_message.create_time IS '创建时间';
COMMENT ON COLUMN ds_user_message.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_um_user_ctime ON ds_user_message (user_id, create_time);
CREATE INDEX IF NOT EXISTS idx_um_user_status ON ds_user_message (user_id, status);

-- 消息群组
CREATE TABLE IF NOT EXISTS ds_group (
    id          BIGSERIAL PRIMARY KEY,
    group_id    VARCHAR(64)      NOT NULL UNIQUE,
    group_name  VARCHAR(128)     NOT NULL,
    remark      VARCHAR(255),
    create_time TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP        DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ds_group IS '消息群组';
COMMENT ON COLUMN ds_group.id IS '主键ID';
COMMENT ON COLUMN ds_group.group_id IS '群组ID';
COMMENT ON COLUMN ds_group.group_name IS '群组名称';
COMMENT ON COLUMN ds_group.remark IS '备注';
COMMENT ON COLUMN ds_group.create_time IS '创建时间';
COMMENT ON COLUMN ds_group.update_time IS '更新时间';

-- 消息群组成员
CREATE TABLE IF NOT EXISTS ds_group_member (
    id          BIGSERIAL PRIMARY KEY,
    group_id    VARCHAR(64)      NOT NULL,
    user_id     VARCHAR(64)      NOT NULL,
    create_time TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_group_member UNIQUE (group_id, user_id)
);

COMMENT ON TABLE ds_group_member IS '消息群组成员';
COMMENT ON COLUMN ds_group_member.id IS '主键ID';
COMMENT ON COLUMN ds_group_member.group_id IS '群组ID';
COMMENT ON COLUMN ds_group_member.user_id IS '用户ID';
COMMENT ON COLUMN ds_group_member.create_time IS '创建时间';

CREATE INDEX IF NOT EXISTS idx_gm_group ON ds_group_member (group_id);
CREATE INDEX IF NOT EXISTS idx_gm_user ON ds_group_member (user_id);

-- 用户快捷操作配置
CREATE TABLE IF NOT EXISTS ds_user_quick_action (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(64)      NOT NULL,
    menu_id     VARCHAR(64)      NOT NULL,
    sort_order  INTEGER          DEFAULT 0,
    create_time TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_user_quick_action UNIQUE (user_id, menu_id)
);

COMMENT ON TABLE ds_user_quick_action IS '用户快捷操作配置';
COMMENT ON COLUMN ds_user_quick_action.id IS '主键ID';
COMMENT ON COLUMN ds_user_quick_action.user_id IS '用户ID';
COMMENT ON COLUMN ds_user_quick_action.menu_id IS '菜单ID';
COMMENT ON COLUMN ds_user_quick_action.sort_order IS '显示顺序';
COMMENT ON COLUMN ds_user_quick_action.create_time IS '创建时间';
COMMENT ON COLUMN ds_user_quick_action.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_uqa_menu ON ds_user_quick_action (menu_id);
CREATE INDEX IF NOT EXISTS idx_uqa_user ON ds_user_quick_action (user_id);

-- ============================================================
-- 种子数据
-- ============================================================

-- V43: 关于我们页面配置
INSERT INTO ds_system_config (config_group, config_key, config_name, config_value, value_type, is_encrypted, is_public, is_enabled, description, sort_order, create_by, update_by)
VALUES
('COMMON', 'business_email', '商务合作邮箱', 'business@beenest.club', 'STRING', false, true, true, '商务合作邮箱', 10, 'system', 'system'),
('COMMON', 'website_url', '官方网站URL', 'www.beenest.club', 'STRING', false, true, true, '官方网站URL', 11, 'system', 'system'),
('COMMON', 'miniapp_description', '小程序介绍', '无人机服务覆盖农业、测绘、物流、消防等多个领域，为用户提供专业、高效、安全的无人机作业服务。', 'STRING', false, true, true, '小程序介绍', 12, 'system', 'system'),
('COMMON', 'copyright', '版权声明', '© 2026 Beenest Technology Co., Ltd. All Rights Reserved.', 'STRING', false, true, true, '版权声明', 13, 'system', 'system'),
('COMMON', 'company_name', '公司名称', 'Beenest Technology Co., Ltd.', 'STRING', false, true, true, '公司名称', 14, 'system', 'system')
ON CONFLICT (config_group, config_key) DO NOTHING;

-- V39: 结算相关系统配置
INSERT INTO ds_system_config (config_group, config_key, config_name, config_value, value_type, is_encrypted, is_public, is_enabled, description, sort_order, create_by, update_by)
VALUES
('SETTLEMENT', 'platform_fee_rate', '平台服务费率', '0.10', 'DECIMAL', false, false, true, '飞手结算时平台收取的服务费比率（0.10表示10%）', 10, 'SYSTEM', 'SYSTEM'),
('SETTLEMENT', 'platform_fee_enabled', '是否启用平台服务费', 'true', 'BOOLEAN', false, false, true, '开启后飞手结算时会扣除平台服务费', 20, 'SYSTEM', 'SYSTEM'),
('SETTLEMENT', 'platform_income_wallet_no', '平台收入钱包编号', 'PLATFORM_INCOME', 'STRING', false, false, true, '平台服务费入账的钱包标识', 30, 'SYSTEM', 'SYSTEM')
ON CONFLICT (config_group, config_key) DO NOTHING;

-- V2.1.0 DML: 联盟加盟费系统配置
INSERT INTO ds_system_config (
    config_group, config_key, config_name, config_value, value_type,
    is_encrypted, is_public, is_enabled, description, sort_order, create_by, update_by
) VALUES
    ('alliance', 'alliance_membership_fee', '联盟加盟费', '99.00', 'DECIMAL',
     FALSE, FALSE, TRUE, '飞手联盟加盟费金额（元）', 10, 'SYSTEM', 'SYSTEM')
ON CONFLICT (config_group, config_key) DO NOTHING;

-- V16: 初始化支付渠道配置
INSERT INTO ds_payment_channel_config
(channel_name, channel_code, app_id, merchant_id, public_key, private_key, notify_url, is_enable)
SELECT '支付宝', 'ALIPAY', 'your_alipay_app_id', 'your_merchant_id', '', '', 'https://api.yourdomain.com/api/payment/alipay/notify', true
WHERE NOT EXISTS (SELECT 1 FROM ds_payment_channel_config WHERE channel_code = 'ALIPAY');

INSERT INTO ds_payment_channel_config
(channel_name, channel_code, app_id, merchant_id, public_key, private_key, notify_url, is_enable)
SELECT '微信支付', 'WECHAT', 'your_wechat_app_id', 'your_mch_id', '', '', 'https://api.yourdomain.com/api/payment/wechat/notify', false
WHERE NOT EXISTS (SELECT 1 FROM ds_payment_channel_config WHERE channel_code = 'WECHAT');

INSERT INTO ds_payment_channel_config
(channel_name, channel_code, app_id, merchant_id, public_key, private_key, notify_url, is_enable)
SELECT '抖音支付', 'DOUYIN', 'your_douyin_app_id', 'your_merchant_id', '', '', 'https://api.yourdomain.com/api/payment/douyin/notify', true
WHERE NOT EXISTS (SELECT 1 FROM ds_payment_channel_config WHERE channel_code = 'DOUYIN');

-- ============================================================
-- 默认角色种子数据（DataInitializer 依赖）
-- ============================================================

INSERT INTO ds_role (role_id, role_name, role_code, description, is_system, is_active)
VALUES
('ROLE_ADMIN', '超级管理员', 'SUPER_ADMIN', '系统超级管理员，拥有所有权限', true, true),
('ROLE_PILOT', '飞手', 'PILOT', '无人机飞手/服务商', true, true),
('ROLE_CUSTOMER', '客户', 'CUSTOMER', '普通客户/下单用户', true, true),
('ROLE_OPERATOR', '运营人员', 'OPERATOR', '平台运营管理人员', true, true)
ON CONFLICT (role_id) DO NOTHING;

-- V2.1.0 DML: 飞手管理权限
INSERT INTO ds_permission (permission_id, permission_code, permission_name, resource_type, description, is_active)
VALUES
    ('PERM_PILOT_PROVIDER_LIST', 'pilot:provider:list', '查看飞手列表', 'api', '查看飞手列表及详情', TRUE),
    ('PERM_PILOT_PROVIDER_UPDATE', 'pilot:provider:update', '修改飞手联盟状态', 'api', '手动修改飞手联盟标志', TRUE),
    ('PERM_PILOT_PAYMENT_LIST', 'pilot:payment:list', '查看飞手支付记录', 'api', '查看飞手支付订单列表', TRUE)
ON CONFLICT (permission_id) DO NOTHING;

-- V2.1.0 DML: 联盟管理菜单
INSERT INTO ds_menu (menu_id, menu_code, menu_name, menu_type, parent_id, path, icon, sort_order, visible, is_active)
VALUES
    ('MENU_ALLIANCE', 'alliance:membership:list', '联盟管理', 'MENU', 'MENU_BUSINESS', '/alliance', 'Users', 6, TRUE, TRUE)
ON CONFLICT (menu_id) DO NOTHING;

-- V2.1.0 DML: 飞手列表菜单
INSERT INTO ds_menu (menu_id, menu_code, menu_name, menu_type, parent_id, path, icon, sort_order, visible, is_active, permission_id)
VALUES
    ('M_PILOT_PROVIDER', 'pilot:provider:list', '飞手列表', 'MENU', 'M_PILOT', '/pilot/providers', 'users', 5, TRUE, TRUE, 'PERM_PILOT_PROVIDER_LIST')
ON CONFLICT (menu_id) DO NOTHING;

-- V2.1.0 DML: 管理员角色分配菜单
INSERT INTO ds_role_menu (role_id, menu_id)
SELECT 'ROLE_ADMIN', 'MENU_ALLIANCE'
ON CONFLICT (role_id, menu_id) DO NOTHING;

INSERT INTO ds_role_menu (role_id, menu_id)
SELECT 'ROLE_ADMIN', 'M_PILOT_PROVIDER'
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- V2.1.0 DML: 管理员角色分配权限
INSERT INTO ds_role_permission (role_id, permission_id)
VALUES
    ('ROLE_ADMIN', 'PERM_PILOT_PROVIDER_LIST'),
    ('ROLE_ADMIN', 'PERM_PILOT_PROVIDER_UPDATE'),
    ('ROLE_ADMIN', 'PERM_PILOT_PAYMENT_LIST')
ON CONFLICT (role_id, permission_id) DO NOTHING;
