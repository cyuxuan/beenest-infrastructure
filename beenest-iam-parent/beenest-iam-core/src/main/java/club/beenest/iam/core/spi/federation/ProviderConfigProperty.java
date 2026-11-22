package club.beenest.iam.core.spi.federation;

/**
 * 提供方配置项声明。
 * <p>用于管理界面渲染配置表单及保存前校验,声明结构随提供方类型固定,
 * 具体值存于 {@code iam_identity_provider.config}。</p>
 *
 * @param name        配置键(如 clientId、clientSecret)
 * @param label       展示名(中文)
 * @param type        值类型
 * @param required    是否必填
 * @param secret      是否敏感值(管理界面回显时脱敏)
 * @param defaultValue 默认值
 * @param helpText    填写说明
 */
public record ProviderConfigProperty(
        String name,
        String label,
        ConfigType type,
        boolean required,
        boolean secret,
        String defaultValue,
        String helpText
) {
    /** 配置值类型: STRING 单行文本,TEXT 多行文本,PASSWORD 敏感值,NUMBER 数字,BOOLEAN 布尔,URL 链接。 */
    public enum ConfigType { STRING, TEXT, PASSWORD, NUMBER, BOOLEAN, URL }
}
