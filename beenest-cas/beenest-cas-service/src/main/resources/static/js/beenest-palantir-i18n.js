(function () {
    const chineseDataTableLanguage = {
        decimal: "",
        emptyTable: "表中没有可用数据",
        info: "显示第 _START_ 到第 _END_ 条，共 _TOTAL_ 条",
        infoEmpty: "显示第 0 到第 0 条，共 0 条",
        infoFiltered: "(从 _MAX_ 条记录中筛选)",
        infoPostFix: "",
        thousands: ",",
        lengthMenu: "每页显示 _MENU_ 条记录",
        loadingRecords: "正在加载...",
        processing: "处理中...",
        search: "搜索：",
        zeroRecords: "没有找到匹配的记录",
        paginate: {
            first: "首页",
            last: "末页",
            next: "下一页",
            previous: "上一页"
        },
        aria: {
            sortAscending: "启用升序排序",
            sortDescending: "启用降序排序"
        }
    };

    const exactTranslations = new Map([
        ["Applications", "应用"],
        ["SAML2 Metadata Sources", "SAML2 元数据来源"],
        ["Service Access Strategy", "服务访问策略"],
        ["Heimdall Authorization", "Heimdall 授权"],
        ["Authentication Handlers", "认证处理器"],
        ["Authentication Policies", "认证策略"],
        ["External Identity Providers", "外部身份提供方"],
        ["Active Profiles", "当前激活配置"],
        ["Environment", "环境"],
        ["Configuration", "配置"],
        ["Security", "安全"],
        ["Search", "搜索"],
        ["Attribute Consent", "属性同意"],
        ["Loggers", "日志器"],
        ["Log Events", "日志事件"],
        ["Audit Events", "审计事件"],
        ["Application Events", "应用事件"],
        ["System Info", "系统信息"],
        ["System Status", "系统状态"],
        ["HTTP Requests", "HTTP 请求"],
        ["Feature Catalog", "功能目录"],
        ["Authentication Flows", "认证流程"],
        ["Attribute Resolution", "属性解析"],
        ["Attribute Definitions", "属性定义"],
        ["Attribute Repositories", "属性仓库"],
        ["CAS Protocol", "CAS 协议"],
        ["SAML1 Protocol", "SAML1 协议"],
        ["SAML2 Protocol", "SAML2 协议"],
        ["OpenID Connect Protocol", "OpenID Connect 协议"],
        ["Throttled Authentications", "限流认证"],
        ["Scheduled Tasks", "调度任务"],
        ["Thread Dump", "线程转储"],
        ["Registered Tenants", "已注册租户"],
        ["Ticket Registry", "票据注册表"],
        ["Ticket Catalog", "票据目录"],
        ["Ticket Expiration Policies", "票据过期策略"],
        ["Applications", "应用"],
        ["MFA Devices", "多因素设备"],
        ["Trusted MFA Devices", "受信任多因素设备"],
        ["Single Sign-on Sessions", "单点登录会话"],
        ["Multifactor Authentication Devices", "多因素身份认证设备"],
        ["Trusted Multifactor Authentication Devices", "受信任多因素身份认证设备"],
        ["Attribute Definitions", "属性定义"],
        ["Attribute Repositories", "属性仓库"],
        ["Service", "服务"],
        ["Username", "用户名"],
        ["Password", "密码"],
        ["Client ID", "客户端 ID"],
        ["Client Secret", "客户端密钥"],
        ["Entity ID", "实体 ID"],
        ["Scope(s)", "作用域"],
        ["Type", "类型"],
        ["Handler", "处理器"],
        ["State", "状态"],
        ["Order", "顺序"],
        ["Policy", "策略"],
        ["Field", "字段"],
        ["Value", "值"],
        ["Value(s)", "值"],
        ["Name", "名称"],
        ["Key", "键"],
        ["Description", "描述"],
        ["Source", "来源"],
        ["Attributes", "属性"],
        ["Date", "日期"],
        ["Options", "选项"],
        ["Reminder", "提醒"],
        ["Principal", "主体"],
        ["Resource", "资源"],
        ["Action", "动作"],
        ["Timestamp", "时间戳"],
        ["ID", "ID"],
        ["URL", "URL"],
        ["URL:", "URL："],
        ["Method", "方法"],
        ["Pattern", "模式"],
        ["Namespace", "命名空间"],
        ["Source", "来源"],
        ["Level", "级别"],
        ["Logger", "日志器"],
        ["Count", "计数"],
        ["Total Time", "总耗时"],
        ["Maximum Time", "最大耗时"],
        ["Active", "活跃"],
        ["Duration", "持续时间"],
        ["Heap Dump", "堆转储"],
        ["Flow:", "流程："],
        ["State:", "状态："],
        ["Refresh:", "刷新："],
        ["Interval:", "间隔："],
        ["Entries:", "条数："],
        ["Log Level:", "日志级别："],
        ["DEFAULT", "默认"],
        ["Last 5 Entries", "最近 5 条"],
        ["Last 15 Entries", "最近 15 条"],
        ["Last 25 Entries", "最近 25 条"],
        ["Last 50 Entries", "最近 50 条"],
        ["Last 100 Entries", "最近 100 条"],
        ["Last 150 Entries", "最近 150 条"],
        ["Last 200 Entries", "最近 200 条"],
        ["Last 500 Entries", "最近 500 条"],
        ["Every 1 Seconds", "每 1 秒"],
        ["Every 5 Seconds", "每 5 秒"],
        ["Every 15 Seconds", "每 15 秒"],
        ["Every 30 Seconds", "每 30 秒"],
        ["Every 60 Seconds", "每 60 秒"],
        ["Last Minute", "最近 1 分钟"],
        ["Last 5 Minute(s)", "最近 5 分钟"],
        ["Last 10 Minutes", "最近 10 分钟"],
        ["Last 30 Minutes", "最近 30 分钟"],
        ["Last Hour", "最近 1 小时"],
        ["Last 2 Hours", "最近 2 小时"],
        ["Last 4 Hours", "最近 4 小时"],
        ["Last 8 Hours", "最近 8 小时"],
        ["Last 24 Hours", "最近 24 小时"],
        ["Create", "创建"],
        ["New", "新建"],
        ["Import", "导入"],
        ["Export", "导出"],
        ["Export All", "导出全部"],
        ["Reload", "刷新"],
        ["Save", "保存"],
        ["History", "历史"],
        ["Changelog", "变更日志"],
        ["Search", "搜索"],
        ["Verify", "验证"],
        ["Clear", "清除"],
        ["Release", "释放"],
        ["Clean", "清理"],
        ["Fetch", "获取"],
        ["Invalidate", "失效"],
        ["Submit", "提交"],
        ["POST Response", "POST 响应"],
        ["Logout Request", "退出请求"],
        ["Exchange", "交换"],
        ["Rotate", "轮换"],
        ["Revoke", "撤销"],
        ["Discovery Profile", "发现配置"],
        ["Registered Service", "已注册服务"],
        ["Attributes", "属性"],
        ["Attributes", "属性"],
        ["Single Sign-on Sessions", "单点登录会话"],
        ["Session", "会话"],
        ["MFA", "MFA"],
        ["SAML2 Entity ID", "SAML2 实体 ID"],
        ["SAML2 Requests/Responses", "SAML2 请求/响应"],
        ["SAML2 Metadata Cache", "SAML2 元数据缓存"],
        ["OpenID Connect Issuer", "OpenID Connect 签发者"],
        ["OpenID Connect JSON web keystore (JWKS).", "OpenID Connect JSON Web 密钥集（JWKS）。"],
        ["OpenID Connect scopes are used to request specific sets of information about the authenticated user and must be separated by spaces. The default scope is openid.", "OpenID Connect 作用域用于请求已认证用户的特定信息集合，多个作用域必须用空格分隔。默认作用域是 `openid`。"],
        ["You can verify whether a user is authorized to access a registered application with CAS.", "您可以验证某个用户是否被授权访问 CAS 中已注册的应用。"],
        ["You can examine the list of authorizable resources and APIs registered with the CAS authorization engine.", "您可以查看 CAS 授权引擎中已注册、可授权的资源与 API 列表。"],
        ["You can examine person attributes from the CAS attribute repositories.", "您可以查看 CAS 属性仓库中的人员属性。"],
        ["You can examine attribute definitions registered with CAS.", "您可以查看 CAS 中注册的属性定义。"],
        ["You can examine attribute repositories registered with CAS that are responsible for fetching attributes for users.", "您可以查看 CAS 中负责为用户获取属性的已注册属性仓库。"],
        ["You can simulate CAS protocol validation responses for CAS-enabled client applications.", "您可以模拟面向启用 CAS 的客户端应用的 CAS 协议校验响应。"],
        ["You can simulate SAML2 responses for SAML2 service providers.", "您可以为 SAML2 服务提供方模拟 SAML2 响应。"],
        ["You can simulate SAML1 responses for SAML1 service providers.", "您可以为 SAML1 服务提供方模拟 SAML1 响应。"],
        ["You can simulate OpenID connect responses to relying party applications and clients.", "您可以为依赖方应用和客户端模拟 OpenID Connect 响应。"],
        ["You can instruct CAS to rotate or revoke keys in the ", "您可以让 CAS 轮换或撤销位于 "],
        ["You can examine the discovery profile of CAS as an OpenID Connect Provider (OP).", "您可以查看 CAS 作为 OpenID Connect 提供方（OP）的发现配置。"],
        ["The following changes are recorded for this service definition:", "以下变更已记录到该服务定义中："],
        ["The above diagram is generated using the following markdown:", "上图由以下 Markdown 生成："],
        ["CAS Server is publicly identified with the following URL:", "CAS 服务器对外标识的 URL："],
        ["CAS Server host currently active and handling requests is:", "当前活跃并处理请求的 CAS 服务器主机是："],
        ["You can ask CAS to encrypt or decrypt a configuration property value for you.", "您可以让 CAS 为您加密或解密某个配置项值。"],
        ["You can search the CAS configuration metadata to query for a configuration property.", "您可以搜索 CAS 配置元数据来查询某个配置项。"],
        ["You can also fetch, refresh or invalidate SAML2 metadata entries that are cached by CAS. The service could be the registered service numeric identifier, its name or actual service id. In case the SAML2 service definition points to an aggregate, you may also specify an entity id to locate the SAML2 service provider within that aggregate. If you do not specify a service, all entries in the SAML2 metadata cache will be invalidated.", "您也可以获取、刷新或失效 CAS 缓存的 SAML2 元数据条目。服务可以是已注册服务的数字 ID、名称或实际 service id；如果 SAML2 服务定义指向一个聚合，还可以指定 entity id 来定位该聚合中的 SAML2 服务提供方。如果不指定服务，SAML2 元数据缓存中的所有条目都会失效。"],
        ["This is the registered service definition that authorized the request. You can", "这是授权该请求的已注册服务定义。您可以"],
        ["This is the registered service definition that matches the request. You can", "这是匹配该请求的已注册服务定义。您可以"],
        ["navigate to it", "跳转到该定义"],
        ["You can examine the list of authorizable resources and APIs registered with the CAS authorization engine.", "您可以查看 CAS 授权引擎中已注册、可授权的资源与 API 列表。"],
        ["Service Access Strategy", "服务访问策略"],
        ["Attribute Repository", "属性仓库"],
        ["Attribute Definitions", "属性定义"],
        ["Attribute Repositories", "属性仓库"],
        ["OpenID Connect Protocol", "OpenID Connect 协议"],
        ["SAML2 Identity Provider Metadata.", "SAML2 身份提供方元数据。"],
        ["CAS protocol validation responses for CAS-enabled client applications.", "面向启用 CAS 的客户端应用的 CAS 协议校验响应。"],
        ["CAS application events retrieved from the event repository.", "从事件仓库检索到的 CAS 应用事件。"],
        ["The latest log statements from CAS application displayed and streamed here.", "CAS 应用的最新日志会在此展示并持续流式输出。"],
        ["The latest audit log statements from CAS application displayed and streamed here.", "CAS 应用的最新审计日志会在此展示并持续流式输出。"],
        ["The CAS application events retrieved from the event repository.", "从事件仓库检索到的 CAS 应用事件。"],
        ["Please wait while Palantir is initializing...", "请稍候，Palantir 正在初始化..."],
        ["Palantir is successfully initialized and is ready for use.", "Palantir 已成功初始化，可以开始使用。"],
        ["Palantir is ready!", "Palantir 已就绪！"],
        ["Initializing Palantir", "正在初始化 Palantir"],
        ["Palantir is unavailable!", "Palantir 暂时不可用！"],
        ["Are you sure you want to import this entry?", "确定要导入这条记录吗？"],
        ["Once imported, the entry should take immediate effect.", "导入后，该记录应立即生效。"],
        ["Are you sure you want to delete this entry?", "确定要删除这条记录吗？"],
        ["Once deleted, you may not be able to recover this entry.", "删除后，这条记录可能无法恢复。"],
        ["Are you sure you want to delete all sessions for the user?", "确定要删除该用户的所有会话吗？"],
        ["Are you sure you want to delete this entry? Once deleted, you may not be able to recover this entry.", "确定要删除这条记录吗？删除后，这条记录可能无法恢复。"],
        ["No History!", "无历史记录！"],
        ["There are no changes recorded for this application definition.", "该应用定义没有记录任何变更。"],
        ["Could not find a registered service with id ", "找不到 ID 为 "],
        ["Status ", "状态 "],
        ["Service is unauthorized.", "服务未授权。"],
        ["You are not authorized to access this resource. Are you sure you are authenticated?", "您没有权限访问该资源。请确认您是否已经完成认证。"],
        ["You are forbidden from accessing this resource. Are you sure you have the necessary permissions and the entry is correctly registered with CAS?", "您被禁止访问该资源。请确认您是否具备所需权限，并且该条目已正确注册到 CAS。"],
        ["Unable to process or accept the request. Check CAS server logs for details.", "无法处理或接受该请求。请查看 CAS 服务器日志获取详情。"],
        ["Unable to contact the CAS server. Are you sure the server is reachable?", "无法连接到 CAS 服务器。请确认服务器是否可达。"],
        ["Unable to make an API call to ", "无法发起到 "],
        ["Is the endpoint enabled and available?", " 的 API 调用。请确认该端点是否已启用并可用。"],
        ["HTTP error: ", "HTTP 错误："],
        ["Message", "消息"],
        ["Query", "查询"],
        ["History", "历史"],
        ["Action", "动作"],
        ["Resource", "资源"],
        ["Principal", "主体"],
        ["Server IP", "服务端 IP"],
        ["Client IP", "客户端 IP"],
        ["User Agent", "用户代理"],
        ["GeoLocation", "地理位置"],
        ["Device Fingerprint", "设备指纹"],
        ["Tickets", "票据"],
        ["Ticket", "票据"],
        ["Clean", "清理"],
        ["Delete Cache", "删除缓存"],
        ["Toggle Token", "切换令牌"],
        ["Search:", "搜索："],
        ["No data available in table", "表中没有可用数据"],
        ["Showing 0 to 0 of 0 entries", "显示第 0 到第 0 条，共 0 条"],
        ["Showing _START_ to _END_ of _TOTAL_ entries", "显示第 _START_ 到第 _END_ 条，共 _TOTAL_ 条"],
        ["Showing _START_ to _END_ of _TOTAL_ entries", "显示第 _START_ 到第 _END_ 条，共 _TOTAL_ 条"]
    ]);

    const regexTranslations = [
        [/^Fetching Devices for (.+)$/, "正在获取 $1 的多因素设备"],
        [/^Fetching Multifactor Trusted Devices for (.+)$/, "正在获取 $1 的受信任多因素设备"],
        [/^Fetching SSO Sessions for (.+)$/, "正在获取 $1 的单点登录会话"],
        [/^Could not find a registered service with id (.+)$/, "找不到 ID 为 $1 的已注册服务"],
        [/^Status (\d+): Service is unauthorized\.$/, "状态 $1：服务未授权。"],
        [/^HTTP error: (\d+)\.$/, "HTTP 错误：$1。"],
        [/^Every (\d+) Seconds$/, "每 $1 秒"],
        [/^Last (\d+) Entries$/, "最近 $1 条"],
        [/^Last (\d+) Minute\(s\)$/, "最近 $1 分钟"],
        [/^Last (\d+) Minutes$/, "最近 $1 分钟"],
        [/^Last (\d+) Hours$/, "最近 $1 小时"],
        [/^Fetching Devices for (.+)$/, "正在获取 $1 的多因素设备"],
        [/^Fetching Multifactor Trusted Devices for (.+)$/, "正在获取 $1 的受信任多因素设备"]
    ];

    const skipTags = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "PRE", "CODE", "TEXTAREA", "TBODY"]);
    let installed = false;
    let retryHandle = null;

    function normalize(value) {
        return value.replace(/\s+/g, " ").trim();
    }

    function preserveWhitespace(original, translated) {
        const leading = original.match(/^\s*/)?.[0] ?? "";
        const trailing = original.match(/\s*$/)?.[0] ?? "";
        return `${leading}${translated}${trailing}`;
    }

    function translatePhrase(value) {
        if (typeof value !== "string" || value.length === 0) {
            return value;
        }

        const normalized = normalize(value);
        if (exactTranslations.has(normalized)) {
            return preserveWhitespace(value, exactTranslations.get(normalized));
        }

        for (const [pattern, replacement] of regexTranslations) {
            const match = normalized.match(pattern);
            if (!match) {
                continue;
            }

            let translated = replacement;
            for (let index = 1; index < match.length; index++) {
                translated = translated.replace(new RegExp(`\\$${index}`, "g"), match[index]);
            }
            return preserveWhitespace(value, translated);
        }

        return value;
    }

    function translateTextNode(node) {
        if (!node || node.nodeType !== Node.TEXT_NODE) {
            return;
        }

        const parent = node.parentElement;
        if (!parent) {
            return;
        }
        if (skipTags.has(parent.tagName) || parent.closest("script,style,noscript,pre,code,textarea")) {
            return;
        }

        const translated = translatePhrase(node.nodeValue);
        if (translated !== node.nodeValue) {
            node.nodeValue = translated;
        }
    }

    function translateAttributes(root) {
        const attributes = ["aria-label", "aria-description", "title", "placeholder", "data-original-title"];
        root.querySelectorAll("*").forEach(element => {
            if (element.closest("script,style,noscript,pre,code,textarea")) {
                return;
            }

            for (const attribute of attributes) {
                if (!element.hasAttribute(attribute)) {
                    continue;
                }

                const currentValue = element.getAttribute(attribute);
                const translated = translatePhrase(currentValue);
                if (translated !== currentValue) {
                    element.setAttribute(attribute, translated);
                }
            }
        });
    }

    function translateDom(root) {
        if (!root) {
            return;
        }

        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        const pending = [];
        while (walker.nextNode()) {
            const textNode = walker.currentNode;
            if (!textNode) {
                continue;
            }
            if (textNode.parentElement && (skipTags.has(textNode.parentElement.tagName) || textNode.parentElement.closest("script,style,noscript,pre,code,textarea"))) {
                continue;
            }

            const translated = translatePhrase(textNode.nodeValue);
            if (translated !== textNode.nodeValue) {
                pending.push([textNode, translated]);
            }
        }

        pending.forEach(([textNode, translated]) => {
            textNode.nodeValue = translated;
        });
        if (root.querySelectorAll) {
            translateAttributes(root);
        }
    }

    function installDataTablesLanguage() {
        if (!window.jQuery || !window.jQuery.fn || !window.jQuery.fn.dataTable) {
            return false;
        }

        window.jQuery.extend(true, window.jQuery.fn.dataTable.defaults, {
            language: chineseDataTableLanguage
        });
        return true;
    }

    function patchSweetAlert() {
        if (!window.Swal || typeof window.Swal.fire !== "function" || window.Swal.__beenestI18nPatched) {
            return;
        }

        const originalSwalFire = window.Swal.fire.bind(window.Swal);
        const translateAlertOptions = options => {
            if (!options || typeof options !== "object") {
                return options;
            }

            const translated = {...options};
            if (typeof translated.title === "string") {
                translated.title = translatePhrase(translated.title);
            }
            if (typeof translated.text === "string") {
                translated.text = translatePhrase(translated.text);
            }
            if (typeof translated.html === "string") {
                translated.html = translatePhrase(translated.html);
            }
            return translated;
        };

        window.Swal.fire = function (...args) {
            if (args.length === 1 && typeof args[0] === "object") {
                return originalSwalFire(translateAlertOptions(args[0]));
            }
            if (args.length >= 2) {
                const [title, text, icon, ...rest] = args;
                const translated = translateAlertOptions({title, text, icon});
                return originalSwalFire(translated.title, translated.text, translated.icon, ...rest);
            }
            return originalSwalFire(...args);
        };
        window.Swal.__beenestI18nPatched = true;
    }

    function patchNotyf() {
        if (!window.Notyf || !window.Notyf.prototype || window.Notyf.prototype.__beenestI18nPatched) {
            return;
        }

        const originalError = window.Notyf.prototype.error;
        window.Notyf.prototype.error = function (message, ...rest) {
            return originalError.call(this, translatePhrase(message), ...rest);
        };
        window.Notyf.prototype.__beenestI18nPatched = true;
    }

    function install() {
        if (installed) {
            return true;
        }

        const ready = installDataTablesLanguage();
        if (!ready) {
            return false;
        }

        patchSweetAlert();
        patchNotyf();
        translateDom(document.body);
        installed = true;
        return true;
    }

    function retryInstall() {
        if (install()) {
            if (retryHandle !== null) {
                window.clearTimeout(retryHandle);
                retryHandle = null;
            }
            return;
        }

        retryHandle = window.setTimeout(retryInstall, 25);
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", retryInstall, {once: true});
    } else {
        retryInstall();
    }
})();
