package club.beenest.iam.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 主题配置属性。
 * <p>每个主题是一个目录(login/error/email 等子页面),切换主题仅需修改
 * {@code iam.theme.active} 并重启。</p>
 * <p>自定义主题:在 classpath(或后续版本的文件系统 themes/)放置同名目录,
 * 零代码接入。</p>
 */
@ConfigurationProperties(prefix = "iam.theme")
public class IamThemeProperties {

    /** 默认主题名 */
    public static final String DEFAULT_THEME = "nestid";

    /** 当前激活主题 */
    private String active = DEFAULT_THEME;

    public String getActive() {
        return active;
    }

    public void setActive(String active) {
        this.active = active;
    }
}
