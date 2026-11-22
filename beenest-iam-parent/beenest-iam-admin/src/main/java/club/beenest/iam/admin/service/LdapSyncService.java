package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.LdapSyncRequest;
import club.beenest.iam.admin.dto.LdapSyncResponse;

/**
 * LDAP/AD 目录用户全量同步服务。
 * <p>把目录中已存在的账号批量落成本地账号,作为 JIT 建号之外的主动铺底;
 * 只补建与补全,不覆盖本地口令、角色与状态。</p>
 */
public interface LdapSyncService {

    /**
     * 执行一次全量同步。
     *
     * @param request 同步参数,可为 null(取缺省上限并实际写入)
     * @return 同步报告;目录不可达等平台故障返回 success=false
     */
    LdapSyncResponse sync(LdapSyncRequest request);
}
