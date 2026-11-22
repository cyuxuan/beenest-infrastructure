package club.beenest.iam.admin.dto;

import java.util.List;

/**
 * LDAP/AD 目录用户全量同步报告。
 * <p>同步只做"补建本地账号、补全空缺资料",不删除、不覆盖本地口令与角色;
 * 目录中已移除的账号仍由管理端手工处理,避免一次误配置清空本地用户。</p>
 *
 * @param success   是否完成同步(目录不可达等平台故障为 false)
 * @param dryRun    是否为预演模式(未写入任何数据)
 * @param truncated 是否因达到 maxEntries 上限而截断
 * @param scanned   从目录读取到的条目数
 * @param created   新建的本地账号数(预演模式下为"将新建")
 * @param updated   补全了资料或目录来源属性的账号数(预演模式下为"将更新")
 * @param unchanged 本地已存在且无需变更的账号数
 * @param skipped   跳过的账号数(本地账号优先、缺少登录名属性等)
 * @param failed    处理失败的账号数(单条失败不影响整体同步)
 * @param durationMs 同步耗时(毫秒)
 * @param samples   有代表性的处理说明(跳过原因、失败原因、邮箱冲突),最多 20 条
 * @param message   面向运维的结果摘要
 */
public record LdapSyncResponse(
        boolean success,
        boolean dryRun,
        boolean truncated,
        int scanned,
        int created,
        int updated,
        int unchanged,
        int skipped,
        int failed,
        long durationMs,
        List<String> samples,
        String message
) {
}
