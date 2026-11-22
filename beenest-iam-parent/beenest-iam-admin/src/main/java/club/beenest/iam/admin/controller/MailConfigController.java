package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.MailConfigResponse;
import club.beenest.iam.admin.dto.MailConfigTestRequest;
import club.beenest.iam.admin.dto.MailConfigUpdateRequest;
import club.beenest.iam.admin.dto.MailTemplateDetailResponse;
import club.beenest.iam.admin.dto.MailTemplateSummaryResponse;
import club.beenest.iam.admin.dto.MailTemplateUpdateRequest;
import club.beenest.iam.admin.service.MailConfigService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 域级发信配置与邮件模板管理接口。
 * <p>口令只写不读:响应不含 SMTP 口令,仅告知是否已配置;
 * 测试邮件用已保存的配置发送,便于把"配置对不对"和"模板对不对"分开排查。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/mail-config")
public class MailConfigController {

    private final MailConfigService mailConfigService;

    public MailConfigController(MailConfigService mailConfigService) {
        this.mailConfigService = mailConfigService;
    }

    /**
     * 查询当前域发信配置。
     *
     * @return 配置响应,不含 SMTP 口令明文
     */
    @GetMapping
    @PreAuthorize("hasAuthority('mail-config:view')")
    public Result<MailConfigResponse> getConfig() {
        return Result.success(mailConfigService.getConfig());
    }

    /**
     * 更新当前域发信配置。
     *
     * @param request 配置更新请求;口令留空表示保持原值
     * @return 更新后的配置
     */
    @PutMapping
    @PreAuthorize("hasAuthority('mail-config:update')")
    @LogAudit(action = "MAIL_CONFIG_UPDATE", description = "更新域级发信配置")
    public Result<MailConfigResponse> updateConfig(@Valid @RequestBody MailConfigUpdateRequest request) {
        return Result.success(mailConfigService.updateConfig(request));
    }

    /**
     * 用已保存的配置发送一封测试邮件。
     *
     * @param request 收件人
     * @return 投递结果与面向运维的可读信息
     */
    @PostMapping("/test")
    @PreAuthorize("hasAuthority('mail-config:update')")
    public Result<MailConfigService.TestOutcome> testSend(@Valid @RequestBody MailConfigTestRequest request) {
        return Result.success(mailConfigService.testSend(request));
    }

    /**
     * 列出全部可覆盖的邮件模板。
     *
     * @return 模板摘要列表
     */
    @GetMapping("/templates")
    @PreAuthorize("hasAuthority('mail-config:view')")
    public Result<List<MailTemplateSummaryResponse>> listTemplates() {
        return Result.success(mailConfigService.listTemplates());
    }

    /**
     * 查询模板当前生效内容。
     *
     * @param code 模板编码
     * @return 模板详情,内容为覆盖版本或内置版本
     */
    @GetMapping("/templates/{code}")
    @PreAuthorize("hasAuthority('mail-config:view')")
    public Result<MailTemplateDetailResponse> getTemplate(@PathVariable String code) {
        return Result.success(mailConfigService.getTemplate(code));
    }

    /**
     * 保存模板覆盖。
     *
     * @param code    模板编码
     * @param request 覆盖内容
     * @return 保存后的模板详情
     */
    @PutMapping("/templates/{code}")
    @PreAuthorize("hasAuthority('mail-config:update')")
    @LogAudit(action = "MAIL_TEMPLATE_UPDATE", description = "更新邮件模板覆盖")
    public Result<MailTemplateDetailResponse> updateTemplate(@PathVariable String code,
                                                             @Valid @RequestBody MailTemplateUpdateRequest request) {
        return Result.success(mailConfigService.updateTemplate(code, request));
    }

    /**
     * 删除模板覆盖,回落到内置模板。
     *
     * @param code 模板编码
     * @return 恢复后的模板详情
     */
    @DeleteMapping("/templates/{code}")
    @PreAuthorize("hasAuthority('mail-config:update')")
    @LogAudit(action = "MAIL_TEMPLATE_RESET", description = "恢复内置邮件模板")
    public Result<MailTemplateDetailResponse> resetTemplate(@PathVariable String code) {
        return Result.success(mailConfigService.resetTemplate(code));
    }
}
