package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * LDAP/AD 目录用户全量同步请求。
 *
 * @param maxEntries 本次最多从目录读取的条目数,防止超大目录拖垮管理端;缺省 2000,上限 20000
 * @param dryRun     预演模式:只统计将新建/更新的账号,不写入任何数据;缺省 false
 */
public record LdapSyncRequest(
        @Min(1) @Max(20000) Integer maxEntries,
        Boolean dryRun
) {
}
