package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.MailConfigResponse;
import club.beenest.iam.admin.dto.MailConfigTestRequest;
import club.beenest.iam.admin.dto.MailConfigUpdateRequest;
import club.beenest.iam.admin.dto.MailTemplateDetailResponse;
import club.beenest.iam.admin.dto.MailTemplateSummaryResponse;
import club.beenest.iam.admin.dto.MailTemplateUpdateRequest;

import java.util.List;

/**
 * 域级发信配置与邮件模板管理服务。
 * <p>配置与模板都限定在当前身份域:多租户部署下各租户的发信服务器与品牌互相独立。</p>
 */
public interface MailConfigService {

    /**
     * 查询当前域发信配置。
     *
     * @return 配置响应,不含 SMTP 口令明文
     */
    MailConfigResponse getConfig();

    /**
     * 更新当前域发信配置。
     *
     * @param request 配置更新请求;口令留空表示保持原值
     * @return 更新后的配置
     */
    MailConfigResponse updateConfig(MailConfigUpdateRequest request);

    /**
     * 用已保存的配置发送一封测试邮件。
     *
     * @param request 收件人
     * @return 投递结果与面向运维的可读信息
     */
    TestOutcome testSend(MailConfigTestRequest request);

    /**
     * 列出全部可覆盖的邮件模板。
     *
     * @return 模板摘要列表
     */
    List<MailTemplateSummaryResponse> listTemplates();

    /**
     * 查询模板当前生效内容。
     *
     * @param code 模板编码
     * @return 模板详情,内容为覆盖版本或内置版本
     */
    MailTemplateDetailResponse getTemplate(String code);

    /**
     * 保存模板覆盖。
     *
     * @param code    模板编码
     * @param request 覆盖内容
     * @return 保存后的模板详情
     */
    MailTemplateDetailResponse updateTemplate(String code, MailTemplateUpdateRequest request);

    /**
     * 删除模板覆盖,回落到内置模板。
     *
     * @param code 模板编码
     * @return 恢复后的模板详情
     */
    MailTemplateDetailResponse resetTemplate(String code);

    /**
     * 测试邮件投递结果。
     *
     * @param success 是否投递成功
     * @param message 面向运维的可读信息
     */
    record TestOutcome(boolean success, String message) {
    }
}
