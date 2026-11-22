-- ============================================================
-- 本迁移:账户自助服务
--   1. iam_user 增加 required_actions:登录后必须先完成的事项,
--      逗号分隔;空串表示没有待办
--   2. 新建 iam_notification_log:邮件/短信投递记录,只追加不更新
-- 语义要点:
--   - required_actions 落在用户行而不是会话里:管理员给账号加"必须改密",
--     该用户下一次登录才生效,但一旦生效要跨设备一致,不能只作用在某个会话
--   - 存量行落空串,升级后无人被强制改密
--   - 投递记录保留失败原因:排查"用户说没收到邮件"时,
--     先要能分清是没发出去还是发出去了被退信
-- ============================================================

ALTER TABLE iam_user
    ADD COLUMN IF NOT EXISTS required_actions VARCHAR(200) NOT NULL DEFAULT '';

COMMENT ON COLUMN iam_user.required_actions IS
    '登录后必须完成的事项,逗号分隔(UPDATE_PASSWORD/VERIFY_EMAIL/CONFIGURE_TOTP);空 = 无待办';

CREATE TABLE IF NOT EXISTS iam_notification_log (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    channel VARCHAR(20) NOT NULL,
    recipient VARCHAR(200) NOT NULL,
    template_code VARCHAR(64) NOT NULL,
    subject VARCHAR(200),
    status VARCHAR(20) NOT NULL,
    error_message VARCHAR(500),
    user_id BIGINT,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_notification_log IS '通知投递记录,只追加;用于排查"用户没收到"类问题';
COMMENT ON COLUMN iam_notification_log.channel IS '投递渠道:EMAIL/SMS';
COMMENT ON COLUMN iam_notification_log.recipient IS '收件地址(邮箱或手机号)';
COMMENT ON COLUMN iam_notification_log.template_code IS '模板标识,对应 themes/{theme}/email/{code}.html 或短信模板';
COMMENT ON COLUMN iam_notification_log.status IS '投递结果:SENT/FAILED';
COMMENT ON COLUMN iam_notification_log.error_message IS '失败原因;成功时为空';
CREATE INDEX IF NOT EXISTS idx_iam_notification_log_realm_time
    ON iam_notification_log(realm_id, created_time DESC) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_iam_notification_log_recipient
    ON iam_notification_log(realm_id, recipient) WHERE deleted = FALSE;
