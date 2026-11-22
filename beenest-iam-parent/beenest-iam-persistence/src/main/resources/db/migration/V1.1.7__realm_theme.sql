-- ============================================================
-- beenest-iam 租户级主题迁移(V1.1.7)
-- iam_realm.theme:本域登录/同意/错误页使用的主题目录名
-- 说明:此前主题是全局单配(iam.theme.active 一个值),多租户共用一张脸。
--       集团多子公司、SaaS 多租户场景下,登录页不带客户品牌是常见商务否决项。
--       本次把主题选择权下沉到域:
--         NULL/空 = 跟随全局 iam.theme.active(行为与升级前一致);
--         非空    = 使用 themes/ 下同名目录,目录不存在时回落全局。
--       只做"按域选已有主题目录",不做主题上传/在线编辑——
--       主题是静态资源包,走部署物流转即可,管理端仅做选择。
-- ============================================================

ALTER TABLE iam_realm ADD COLUMN IF NOT EXISTS theme VARCHAR(64);
COMMENT ON COLUMN iam_realm.theme IS
        '本域协议页主题目录名(themes/ 下同名目录);NULL 或空表示跟随全局 iam.theme.active';
