package club.beenest.iam.authz.notification;

import club.beenest.iam.persistence.manager.IamMailTemplateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邮件模板渲染器。
 * <p>模板优先取域级覆盖(iam_mail_template),没有覆盖时用
 * {@code classpath:/mail-templates/{code}.html};{@code {{变量名}}} 为占位,
 * 变量值按 HTML 转义,加载后的内置模板缓存,未赋值的占位符渲染时清除。</p>
 */
@Component
public class MailTemplateRenderer {

    private static final Logger log = LoggerFactory.getLogger(MailTemplateRenderer.class);

    /** 模板根目录 */
    private static final String TEMPLATE_ROOT = "mail-templates/";
    /** 模板文件后缀 */
    private static final String TEMPLATE_SUFFIX = ".html";

    /** 域级覆盖读取;为 null 表示只读内置模板(测试替身场景) */
    private final IamMailTemplateStore templateStore;
    /** 已加载的内置模板原文 */
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    /** 测试替身用:不读取域级覆盖。 */
    public MailTemplateRenderer() {
        this(null);
    }

    @Autowired
    public MailTemplateRenderer(IamMailTemplateStore templateStore) {
        this.templateStore = templateStore;
    }

    /**
     * 渲染指定身份域生效的模板。
     *
     * @param realmId   身份域主键;为空时只用内置模板
     * @param code      模板标识(不含路径与后缀)
     * @param variables 占位变量;值会被 HTML 转义
     * @return 渲染后的 HTML;模板不存在时返回 null
     */
    public String render(Long realmId, String code, Map<String, Object> variables) {
        String template = load(realmId, code);
        if (template == null) {
            return null;
        }
        String result = template;
        if (variables != null) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                result = result.replace("{{" + entry.getKey() + "}}", escapeHtml(value));
            }
        }
        // 清掉未被赋值的占位符
        return result.replaceAll("\\{\\{[a-zA-Z0-9_]+}}", "");
    }

    /**
     * 渲染内置模板。
     *
     * @param code      模板标识(不含路径与后缀)
     * @param variables 占位变量;值会被 HTML 转义
     * @return 渲染后的 HTML;模板不存在时返回 null
     */
    public String render(String code, Map<String, Object> variables) {
        return render(null, code, variables);
    }

    /**
     * 模板是否存在。
     *
     * @param code 模板标识
     * @return 是否存在(内置模板或任一域覆盖过都算存在)
     */
    public boolean exists(String code) {
        return load(null, code) != null;
    }

    /**
     * 域内生效的模板原文:域覆盖优先,其次内置模板。
     *
     * @param realmId 身份域主键;为空时只读内置模板
     * @param code    模板标识
     * @return 模板原文;不存在时返回 null
     */
    public String effectiveContent(Long realmId, String code) {
        return load(realmId, code);
    }

    /**
     * 读取模板原文。
     * <p>覆盖内容不缓存:管理员改完就应生效,读一次库比等缓存过期便宜。</p>
     *
     * @param realmId 身份域主键;为空时跳过覆盖
     * @param code    模板标识
     * @return 模板原文;不存在或读取失败时返回 null
     */
    private String load(Long realmId, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        if (realmId != null && templateStore != null) {
            try {
                String override = templateStore.findContent(realmId, code);
                if (override != null && !override.isBlank()) {
                    return override;
                }
            } catch (RuntimeException e) {
                // 覆盖读取失败不能导致邮件全部发不出去:回落到内置模板
                log.warn("读取邮件模板覆盖失败,回落内置模板: realmId={}, code={}, error={}",
                        realmId, code, e.getMessage());
            }
        }
        return loadBuiltin(code);
    }

    /**
     * 读取内置模板原文(带缓存)。
     *
     * @param code 模板标识
     * @return 模板原文;不存在或读取失败时返回 null
     */
    private String loadBuiltin(String code) {
        return templateCache.computeIfAbsent(code, key -> {
            String path = TEMPLATE_ROOT + key + TEMPLATE_SUFFIX;
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                return null;
            }
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        });
    }

    /**
     * HTML 转义:变量中可能含用户可控内容(如昵称)。
     *
     * @param value 原始值
     * @return 转义结果
     */
    private static String escapeHtml(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
