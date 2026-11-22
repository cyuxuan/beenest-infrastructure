-- 回滚 V1.1.6:移除会话巡检索引。
-- 索引删除不影响数据;会话巡检任务会退回全表扫描,
-- 会话量大时该任务耗时会明显上升,但功能不受影响。

DROP INDEX IF EXISTS idx_iam_user_session_active;
