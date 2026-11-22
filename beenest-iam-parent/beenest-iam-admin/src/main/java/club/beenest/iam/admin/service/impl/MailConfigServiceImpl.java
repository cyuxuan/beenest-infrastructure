package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.MailConfigResponse;
import club.beenest.iam.admin.dto.MailConfigTestRequest;
import club.beenest.iam.admin.dto.MailConfigUpdateRequest;
import club.beenest.iam.admin.dto.MailTemplateDetailResponse;
import club.beenest.iam.admin.dto.MailTemplateSummaryResponse;
import club.beenest.iam.admin.dto.MailTemplateUpdateRequest;
import club.beenest.iam.admin.service.MailConfigService;
import club.beenest.iam.authz.mail.MailChannelRouter;
import club.beenest.iam.authz.notification.MailTemplateRenderer;
import club.beenest.iam.authz.notification.NotificationService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.security.IamSecretCrypto;
import club.beenest.iam.core.spi.mail.MailChannel;
import club.beenest.iam.persistence.entity.MailTemplateOverride;
import club.beenest.iam.persistence.entity.RealmMailConfig;
import club.beenest.iam.persistence.manager.IamMailTemplateStore;
import club.beenest.iam.persistence.manager.IamRealmMailConfigStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 域级发信配置管理实现。
 * <p>SMTP 口令经 {@link IamSecretCrypto} 信封加密后落库,响应只返回是否已配置;
 * 更新时口令留空表示保持原值。</p>
 */
@Service
public class MailConfigServiceImpl implements MailConfigService {

    /** 模板目录:编码、展示名与必须保留的占位符 */
    private static final List<TemplateMeta> TEMPLATES = List.of(
            new TemplateMeta(NotificationService.TEMPLATE_PASSWORD_RESET, "找回口令", List.of("resetUrl")),
            new TemplateMeta(NotificationService.TEMPLATE_EMAIL_VERIFICATION, "邮箱验证", List.of("verifyUrl")),
            new TemplateMeta(NotificationService.TEMPLATE_SECURITY_ALERT, "安全提醒", List.of()),
            new TemplateMeta(NotificationService.TEMPLATE_ACCOUNT_OPENED, "账号开通", List.of()));

    /** 模板原文占位符的匹配形态 */
    private static final String PLACEHOLDER_FORMAT = "{{%s}}";

    private final IamRealmMailConfigStore mailConfigStore;
    private final IamMailTemplateStore mailTemplateStore;
    private final IamSecretCrypto secretCrypto;
    private final MailChannelRouter mailChannelRouter;
    private final MailTemplateRenderer templateRenderer;

    public MailConfigServiceImpl(IamRealmMailConfigStore mailConfigStore,
                                 IamMailTemplateStore mailTemplateStore,
                                 IamSecretCrypto secretCrypto,
                                 MailChannelRouter mailChannelRouter,
                                 MailTemplateRenderer templateRenderer) {
        this.mailConfigStore = mailConfigStore;
        this.mailTemplateStore = mailTemplateStore;
        this.secretCrypto = secretCrypto;
        this.mailChannelRouter = mailChannelRouter;
        this.templateRenderer = templateRenderer;
    }

    @Override
    public MailConfigResponse getConfig() {
        RealmMailConfig config = mailConfigStore.ensureConfig(CurrentUserHolder.require().getRealmId());
        return toResponse(config);
    }

    @Override
    public MailConfigResponse updateConfig(MailConfigUpdateRequest request) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        RealmMailConfig config = mailConfigStore.ensureConfig(realmId);
        config.setEnabled(Boolean.TRUE.equals(request.enabled()));
        config.setHost(trimToEmpty(request.host()));
        config.setPort(request.port());
        config.setUsername(trimToEmpty(request.username()));
        // 口令留空表示不修改:密文不回显,管理端也就无法"原样提交"
        if (request.password() != null && !request.password().isBlank()) {
            config.setPassword(secretCrypto.encrypt(MailChannelRouter.PASSWORD_PURPOSE,
                    String.valueOf(realmId), request.password()));
        }
        config.setFromAddress(trimToEmpty(request.fromAddress()));
        config.setFromName(trimToEmpty(request.fromName()));
        config.setStarttls(Boolean.TRUE.equals(request.starttls()));
        config.setSsl(Boolean.TRUE.equals(request.ssl()));
        config.setTimeoutMillis(request.timeoutMillis());
        mailConfigStore.updateConfig(config);
        // 发送器缓存按更新时间比对,这里主动丢弃让旧口令尽快离开内存
        mailChannelRouter.evict(realmId);
        return toResponse(mailConfigStore.ensureConfig(realmId));
    }

    @Override
    public TestOutcome testSend(MailConfigTestRequest request) {
        var principal = CurrentUserHolder.require();
        MailChannel channel = mailChannelRouter.resolve(principal.getRealmId()).orElse(null);
        if (channel == null) {
            return new TestOutcome(false, "当前域与全局都没有可用的发信配置,请先保存并启用本域 SMTP 配置");
        }
        String html = "<p>这是一封来自统一身份认证的测试邮件。</p>"
                + "<p>收到它说明当前身份域的发信配置可用。</p>";
        try {
            channel.send(request.to(), "统一身份认证测试邮件", html, principal.getRealmKey());
            return new TestOutcome(true, "测试邮件已发送至 " + request.to() + ",请检查收件箱与垃圾邮件目录");
        } catch (RuntimeException e) {
            // 测试端点把真实错误原样带回:排障时最需要的就是它
            return new TestOutcome(false, e.getMessage());
        }
    }

    @Override
    public List<MailTemplateSummaryResponse> listTemplates() {
        Long realmId = CurrentUserHolder.require().getRealmId();
        Map<String, Boolean> overridden = overriddenFlags(realmId);
        List<MailTemplateSummaryResponse> result = new ArrayList<>(TEMPLATES.size());
        for (TemplateMeta meta : TEMPLATES) {
            result.add(new MailTemplateSummaryResponse(meta.code(), meta.name(),
                    overridden.getOrDefault(meta.code(), false)));
        }
        return result;
    }

    @Override
    public MailTemplateDetailResponse getTemplate(String code) {
        TemplateMeta meta = requireTemplate(code);
        Long realmId = CurrentUserHolder.require().getRealmId();
        String content = templateRenderer.effectiveContent(realmId, meta.code());
        if (content == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "模板不存在: " + code);
        }
        return new MailTemplateDetailResponse(meta.code(), meta.name(),
                overriddenFlags(realmId).getOrDefault(meta.code(), false), content);
    }

    @Override
    public MailTemplateDetailResponse updateTemplate(String code, MailTemplateUpdateRequest request) {
        TemplateMeta meta = requireTemplate(code);
        requirePlaceholders(meta, request.content());
        Long realmId = CurrentUserHolder.require().getRealmId();
        mailTemplateStore.save(realmId, meta.code(), request.content());
        return getTemplate(meta.code());
    }

    @Override
    public MailTemplateDetailResponse resetTemplate(String code) {
        TemplateMeta meta = requireTemplate(code);
        mailTemplateStore.reset(CurrentUserHolder.require().getRealmId(), meta.code());
        return getTemplate(meta.code());
    }

    /**
     * 校验模板编码属于可覆盖清单。
     *
     * @param code 模板编码
     * @return 模板元数据
     * @throws BusinessException 编码不在清单内
     */
    private TemplateMeta requireTemplate(String code) {
        return TEMPLATES.stream()
                .filter(meta -> meta.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "未知模板编码: " + code));
    }

    /**
     * 校验模板保留了功能性占位符。
     * <p>找回口令与邮箱验证的链接就藏在占位符里,占位符被删掉等于功能坏掉,
     * 而模板本身依然是合法 HTML,不做校验就只能等用户投诉才发现。</p>
     *
     * @param meta    模板元数据
     * @param content 模板原文
     * @throws BusinessException 缺少必须保留的占位符
     */
    private void requirePlaceholders(TemplateMeta meta, String content) {
        for (String variable : meta.requiredPlaceholders()) {
            if (!content.contains(String.format(PLACEHOLDER_FORMAT, variable))) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "模板必须保留占位符 " + String.format(PLACEHOLDER_FORMAT, variable));
            }
        }
    }

    /**
     * 查询域内已覆盖的模板编码。
     *
     * @param realmId 身份域主键
     * @return 编码到是否覆盖的映射
     */
    private Map<String, Boolean> overriddenFlags(Long realmId) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        for (MailTemplateOverride override : mailTemplateStore.listByRealm(realmId)) {
            flags.put(override.getTemplateCode(), true);
        }
        return flags;
    }

    /**
     * 配置实体转响应。
     *
     * @param config 配置实体
     * @return 配置响应
     */
    private MailConfigResponse toResponse(RealmMailConfig config) {
        return new MailConfigResponse(
                Boolean.TRUE.equals(config.getEnabled()),
                config.getHost() == null ? "" : config.getHost(),
                config.getPort() == null ? 587 : config.getPort(),
                config.getUsername() == null ? "" : config.getUsername(),
                config.getPassword() != null && !config.getPassword().isBlank(),
                config.getFromAddress() == null ? "" : config.getFromAddress(),
                config.getFromName() == null ? "" : config.getFromName(),
                !Boolean.FALSE.equals(config.getStarttls()),
                Boolean.TRUE.equals(config.getSsl()),
                config.getTimeoutMillis() == null ? 10_000 : config.getTimeoutMillis(),
                mailChannelRouter.available());
    }

    /**
     * 去掉首尾空白,空值转空串(便于管理端清空字段)。
     *
     * @param value 原值
     * @return 处理后的值
     */
    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 可覆盖模板的元数据。
     *
     * @param code                  模板编码
     * @param name                  模板名称
     * @param requiredPlaceholders  必须保留的占位符
     */
    private record TemplateMeta(String code, String name, List<String> requiredPlaceholders) {
    }
}
