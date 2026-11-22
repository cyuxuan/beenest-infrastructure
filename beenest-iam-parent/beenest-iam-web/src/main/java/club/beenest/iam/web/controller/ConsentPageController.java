package club.beenest.iam.web.controller;

import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.web.config.IamThemeSelector;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 授权同意页控制器。
 * <p>授权服务器在客户端开启 {@code require_consent} 且用户尚未同意时,
 * 会重定向到本页并携带 {@code client_id / scope / state}。
 * 本页只负责展示与回传:勾选后的 scope 提交回授权端点,由协议栈写入同意记录并签发授权码。</p>
 * <p>同意记录存放在协议层的授权同意存储中,管理端可查询与撤销
 * ({@code /api/v1/admin/consents}),因此本页不直接读写该存储,避免两套写入逻辑。</p>
 */
@Controller
public class ConsentPageController {

    /**
     * 已登记释义的 scope;未登记的 scope 按原样展示,不影响授权。
     * <p>不含 {@code offline_access}:平台不提供离线令牌语义,客户端登记时已被拒绝,
     * 这里也不给它一个"看起来支持"的文案。</p>
     */
    private static final Set<String> KNOWN_SCOPES =
            Set.of("openid", "profile", "email", "phone", "address");

    private final RealmProvider realmProvider;
    private final ClientProvider clientProvider;
    private final IamThemeSelector themeSelector;
    private final MessageSource messageSource;

    public ConsentPageController(RealmProvider realmProvider, ClientProvider clientProvider,
                                 IamThemeSelector themeSelector, MessageSource messageSource) {
        this.realmProvider = realmProvider;
        this.clientProvider = clientProvider;
        this.themeSelector = themeSelector;
        this.messageSource = messageSource;
    }

    /**
     * 渲染授权同意页。
     *
     * @param clientId 客户端标识(授权服务器重定向时携带)
     * @param scope    申请的 scope,空格分隔;为空表示不申请任何范围
     * @param state    授权请求状态串,同意后原样回传(防 CSRF 与绑定原始请求);为空时按空串回传
     * @param realmKey 身份域标识;缺省 default(授权端点路径已带域,此处仅用于文案与回传)
     * @param model    视图模型
     * @return 主题化同意页视图名
     */
    @GetMapping("/consent")
    public String consentPage(@RequestParam("client_id") String clientId,
                              @RequestParam(value = "scope", required = false) String scope,
                              @RequestParam(value = "state", required = false) String state,
                              @RequestParam(value = "realm", required = false) String realmKey,
                              Model model) {
        // 1. 解析身份域:参数优先,缺省 default
        String resolvedRealm = (realmKey == null || realmKey.isBlank()) ? "default" : realmKey;
        RealmModel realm = realmProvider.findByRealmKey(resolvedRealm).orElse(null);
        // 2. 客户端展示名;查不到时回落 client_id,页面仍可完成授权
        String clientName = clientId;
        if (realm != null) {
            ClientModel client = clientProvider.findByClientKey(realm.id(), clientId).orElse(null);
            if (client != null) {
                clientName = client.clientName();
            }
        }
        // 3. scope 拆解:openid 为必选(身份认证前提),其余逐项让用户确认
        List<Map<String, String>> scopes = new ArrayList<>();
        for (String item : splitScopes(scope)) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("code", item);
            row.put("label", scopeText(item, "label"));
            row.put("description", scopeText(item, "desc"));
            row.put("required", "openid".equals(item) ? "true" : "false");
            scopes.add(row);
        }
        model.addAttribute("realmKey", resolvedRealm);
        model.addAttribute("clientId", clientId);
        model.addAttribute("clientName", clientName);
        model.addAttribute("state", state == null ? "" : state);
        model.addAttribute("scopes", scopes);
        model.addAttribute("principalName", currentUsername());
        return themeSelector.view("consent/consent");
    }

    /**
     * 取 scope 的展示文案。
     *
     * @param scope  scope 代码
     * @param suffix 文案种类,{@code label}(名称)或 {@code desc}(用途说明)
     * @return 当前请求语言下的文案;未登记的 scope 名称为其代码本身,说明回落通用描述
     */
    private String scopeText(String scope, String suffix) {
        if (!KNOWN_SCOPES.contains(scope)) {
            return "label".equals(suffix) ? scope : messageSource.getMessage(
                    "page.consent.scope.other.desc", null, "访问该应用声明的资源", LocaleContextHolder.getLocale());
        }
        String key = "page.consent.scope." + scope + "." + suffix;
        return messageSource.getMessage(key, null, scope, LocaleContextHolder.getLocale());
    }

    /**
     * 拆分空格分隔的 scope,去重并保序。
     *
     * @param scope 原始 scope 串
     * @return scope 列表;入参为空时返回空列表
     */
    private static List<String> splitScopes(String scope) {
        if (scope == null || scope.isBlank()) {
            return List.of();
        }
        return Arrays.stream(scope.trim().split("\\s+"))
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    /**
     * 当前登录账号名(用于页面标识"谁在授权")。
     *
     * @return 账号名;未认证时为空串
     */
    private static String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "" : authentication.getName();
    }
}
