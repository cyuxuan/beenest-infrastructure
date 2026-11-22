package club.beenest.iam.authz.saml;

import org.springframework.web.util.HtmlUtils;

/**
 * SAML HTTP-POST 绑定的自动提交页。
 * <p>断言由本页以表单 POST 交给 SP 的断言消费地址,浏览器只做转交。
 * 页面是协议的一部分而不是产品界面,因此不走主题体系:换主题不能改变报文形态。</p>
 */
public final class SamlPostBindingPage {

    /** 提交给 SP 的参数名(协议固定) */
    private static final String PARAM_SAML_RESPONSE = "SAMLResponse";

    /** 提交给 SP 的不透明回传参数名(协议固定) */
    private static final String PARAM_RELAY_STATE = "RelayState";

    private SamlPostBindingPage() {
    }

    /**
     * 生成自动提交页。
     *
     * @param actionUrl     SP 的断言消费地址
     * @param samlResponse  Base64 编码的 SAMLResponse
     * @param relayState    原样回传的 RelayState;为空则不输出该字段
     * @param displayName   SP 展示名,用于提示文案
     * @return HTML 文本
     */
    public static String render(String actionUrl, String samlResponse, String relayState, String displayName) {
        StringBuilder html = new StringBuilder(1024);
        html.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n")
                .append("<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>正在跳转</title>\n")
                .append("</head>\n")
                .append("<body onload=\"document.forms[0].submit()\">\n")
                .append("<p>正在跳转到").append(HtmlUtils.htmlEscape(safeName(displayName))).append("…</p>\n")
                .append("<form method=\"post\" action=\"").append(HtmlUtils.htmlEscape(actionUrl)).append("\">\n")
                .append("<input type=\"hidden\" name=\"").append(PARAM_SAML_RESPONSE).append("\" value=\"")
                .append(HtmlUtils.htmlEscape(samlResponse)).append("\">\n");
        if (relayState != null && !relayState.isBlank()) {
            html.append("<input type=\"hidden\" name=\"").append(PARAM_RELAY_STATE).append("\" value=\"")
                    .append(HtmlUtils.htmlEscape(relayState)).append("\">\n");
        }
        html.append("<noscript><button type=\"submit\">继续</button></noscript>\n")
                .append("</form>\n</body>\n</html>\n");
        return html.toString();
    }

    /**
     * 展示名兜底:未配置时给出通用称呼,避免页面出现空白。
     *
     * @param displayName SP 展示名
     * @return 非空展示名
     */
    private static String safeName(String displayName) {
        return displayName == null || displayName.isBlank() ? "应用" : displayName;
    }
}
