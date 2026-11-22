package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.LdapConfigResponse;
import club.beenest.iam.admin.dto.LdapConfigUpdateRequest;
import club.beenest.iam.admin.dto.LdapSyncRequest;
import club.beenest.iam.admin.dto.LdapSyncResponse;
import club.beenest.iam.admin.service.LdapConfigService;
import club.beenest.iam.admin.service.LdapSyncService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LDAP/AD 用户联合配置管理接口(每域一条)。
 * <p>口令只写不读:响应不含 bind 口令,仅告知是否已配置;
 * 测试连接用表单值直连目录,未保存也可验证。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/ldap-config")
public class LdapConfigController {

    private final LdapConfigService ldapConfigService;
    private final LdapSyncService ldapSyncService;

    public LdapConfigController(LdapConfigService ldapConfigService, LdapSyncService ldapSyncService) {
        this.ldapConfigService = ldapConfigService;
        this.ldapSyncService = ldapSyncService;
    }

    /**
     * 查询当前域 LDAP 联合配置。
     *
     * @return 配置响应,不含 bind 口令明文,仅告知是否已配置
     */
    @GetMapping
    @PreAuthorize("hasAuthority('ldap-config:view')")
    public Result<LdapConfigResponse> getConfig() {
        return Result.success(ldapConfigService.getConfig());
    }

    /**
     * 更新当前域 LDAP 联合配置。
     *
     * @param request 配置更新请求;bind 口令留空表示保持原值,非空时加密后覆盖
     * @return 更新后的配置
     */
    @PutMapping
    @PreAuthorize("hasAuthority('ldap-config:update')")
    @LogAudit(action = "LDAP_CONFIG_UPDATE", description = "更新 LDAP 用户联合配置")
    public Result<LdapConfigResponse> updateConfig(@Valid @RequestBody LdapConfigUpdateRequest request) {
        return Result.success(ldapConfigService.updateConfig(request));
    }

    /**
     * 用当前表单值测试目录连接(不落库)。
     * <p>入口权限设为 {@code ldap-config:update} 而非 {@code view}:该端点会让服务端
     * 主动连接表单指定的地址,且表单地址与库中记录一致时可复用已保存的 bind 口令。</p>
     *
     * @param request 表单配置请求
     * @return 测试结果,含面向运维的可读信息
     */
    @PostMapping("/test")
    @PreAuthorize("hasAuthority('ldap-config:update')")
    @LogAudit(action = "LDAP_CONFIG_TEST", description = "测试 LDAP 目录连接")
    public Result<LdapConfigService.ConnectionOutcome> testConnection(
            @Valid @RequestBody LdapConfigUpdateRequest request) {
        return Result.success(ldapConfigService.testConnection(request));
    }

    /**
     * 把目录用户全量同步为本地账号(只补建与补全,不删除、不覆盖本地口令)。
     * <p>{@code dryRun=true} 时只统计将发生的变更,不写入任何数据,用于正式同步前评估影响面。</p>
     *
     * @param request 同步参数;请求体可省略,默认读取 2000 条并实际写入
     * @return 同步报告,含新建/更新/跳过/失败计数与样例说明
     */
    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('ldap-config:update')")
    @LogAudit(action = "LDAP_SYNC", description = "同步 LDAP 目录用户")
    public Result<LdapSyncResponse> sync(@Valid @RequestBody(required = false) LdapSyncRequest request) {
        return Result.success(ldapSyncService.sync(request));
    }
}
