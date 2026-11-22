package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.ClientDtoConverter;
import club.beenest.iam.admin.dto.ClientCreateRequest;
import club.beenest.iam.admin.dto.ClientResponse;
import club.beenest.iam.admin.dto.ClientTypeChangeRequest;
import club.beenest.iam.admin.dto.ClientUpdateRequest;
import club.beenest.iam.admin.service.ClientService;
import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.admission.LoginMethodCatalog;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.Client;
import club.beenest.iam.persistence.entity.ClientReferenceCount;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.converter.ClientConverter;
import club.beenest.iam.persistence.manager.IamClientManager;
import club.beenest.iam.persistence.manager.IamClientScopeManager;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户端管理实现。
 * <p>客户端标识在域内唯一;机密客户端的密钥只存密文,明文仅在创建、轮换与类型升级时返回。
 * 创建与更新均校验凭据模式、登出地址与准入白名单。类型决定令牌端点的认证方式,
 * 因此不并入通用更新,只在客户端尚未投入使用时允许变更。</p>
 */
@Service
public class ClientServiceImpl implements ClientService {

    /** 客户端类型:机密 */
    private static final String TYPE_CONFIDENTIAL = "CONFIDENTIAL";

    /** 客户端类型:公开 */
    private static final String TYPE_PUBLIC = "PUBLIC";

    /** 授权类型:凭据模式,仅机密客户端可用且必须绑定服务账号 */
    private static final String GRANT_CLIENT_CREDENTIALS = "client_credentials";

    /** 授权类型缺省组合:与 DDL 默认值、{@code ClientConverter#grantTypesOf} 保持一致 */
    private static final List<String> DEFAULT_GRANT_TYPES =
            List.of("authorization_code", "refresh_token");

    /** 离线访问 scope:平台不实现该语义,登记时直接拒绝 */
    private static final String OFFLINE_ACCESS_SCOPE = "offline_access";

    private final IamClientManager clientManager;
    private final IamClientScopeManager clientScopeManager;
    private final ClientDtoConverter dtoConverter;
    private final ClientConverter clientConverter;
    private final IamUserManager userManager;
    private final LoginMethodCatalog loginMethodCatalog;

    public ClientServiceImpl(IamClientManager clientManager, IamClientScopeManager clientScopeManager,
                             ClientDtoConverter dtoConverter, ClientConverter clientConverter,
                             IamUserManager userManager, LoginMethodCatalog loginMethodCatalog) {
        this.clientManager = clientManager;
        this.clientScopeManager = clientScopeManager;
        this.dtoConverter = dtoConverter;
        this.clientConverter = clientConverter;
        this.userManager = userManager;
        this.loginMethodCatalog = loginMethodCatalog;
    }

    @Override
    public PageResult<ClientResponse> page(String clientKey, String clientName, String clientType,
                                           Boolean enabled, int pageNum, int pageSize) {
        // 1. 组装单表查询条件
        Client condition = new Client();
        condition.setClientKey(clientKey);
        condition.setClientName(clientName);
        condition.setClientType(clientType);
        condition.setEnabled(enabled);
        // 2. 分页查询当前域客户端后逐条转换(不回显密钥)
        PageResult<Client> page = clientManager.page(currentRealmId(), condition, pageNum, pageSize);
        return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(), page.getPages(),
                page.getRecords().stream().map(dtoConverter::toResponse).toList());
    }

    @Override
    public ClientResponse get(Long id) {
        return dtoConverter.toResponse(requireClientInRealm(id));
    }

    @Override
    public ClientResponse create(ClientCreateRequest request) {
        Long realmId = currentRealmId();
        // 1. 客户端标识在域内唯一
        if (clientManager.getByClientKey(realmId, request.clientKey()) != null) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "客户端标识已存在: " + request.clientKey());
        }
        // 2. 回调与登出地址先过形态校验,再落库
        validateRedirectUris(request.redirectUris());
        validateLogoutTargets(request.postLogoutRedirectUris(), request.backchannelLogoutUri());
        // 2.1 作用域口径校验
        validateScopes(request.scopes());
        // 3. 凭据模式的前置校验:必须是机密客户端,且绑定同域的服务账号主体
        validateClientCredentials(request.clientType(), request.grantTypes(), request.serviceAccountUserId(), realmId);
        // 4. 准入白名单收敛校验:与域级可用方式无交集时拒绝
        validateAdmissionWhitelist(request.allowedLoginTypes(), realmId);
        // 5. 落库;机密客户端由持久层生成密钥并只存密文
        Client client = dtoConverter.toEntity(request, realmId);
        String plainSecret = clientManager.createWithSecret(client);
        // 6. 明文密钥仅在本次响应中返回
        return dtoConverter.toResponse(clientManager.getById(client.getId()), plainSecret);
    }

    @Override
    public ClientResponse update(Long id, ClientUpdateRequest request) {
        // 1. 目标客户端必须在当前域
        Client client = requireClientInRealm(id);
        // 2. 回调与登出地址先过形态校验
        validateRedirectUris(request.redirectUris());
        validateLogoutTargets(request.postLogoutRedirectUris(), request.backchannelLogoutUri());
        // 2.1 作用域口径校验
        validateScopes(request.scopes());
        // 3. 更新时同样校验收敛性(白名单可能被改成与域级无交集)
        validateAdmissionWhitelist(request.allowedLoginTypes(), client.getRealmId());
        // 4. 更新路径同样执行凭据模式校验:客户端类型取库中值,
        //    授权类型与服务账号取更新后的生效值
        validateClientCredentials(client.getClientType(),
                effectiveGrantTypes(client, request.grantTypes()),
                effectiveServiceAccountUserId(client, request.serviceAccountUserId()),
                client.getRealmId());
        // 5. 仅更新显式给出的字段
        dtoConverter.applyUpdate(client, request);
        clientManager.update(client);
        return dtoConverter.toResponse(clientManager.getById(id));
    }

    /**
     * 计算更新后的生效授权类型。
     * <p>请求未给出时沿用库中值,传空数组时回落到默认组合。</p>
     *
     * @param client    库中客户端
     * @param requested 请求给出的授权类型,可为 null
     * @return 生效的授权类型
     */
    private List<String> effectiveGrantTypes(Client client, List<String> requested) {
        if (requested != null) {
            return requested.isEmpty() ? DEFAULT_GRANT_TYPES : requested;
        }
        return List.copyOf(clientConverter.grantTypesOf(client.getGrantTypes()));
    }

    /**
     * 计算更新后的生效服务账号主键。
     *
     * @param client    库中客户端
     * @param requested 请求给出的服务账号主键,可为 null
     * @return 生效的服务账号主键
     */
    private Long effectiveServiceAccountUserId(Client client, Long requested) {
        return requested != null ? requested : client.getServiceAccountUserId();
    }

    /**
     * 校验客户端作用域口径。
     * <p>拒绝 {@code offline_access}:该 scope 的语义是"刷新令牌不随会话结束失效",
     * 而本平台的安全口径恰恰相反——登出与超时即令令牌失效。允许登记而不实现语义,
     * 会让接入方以为拿到了长期令牌,实际在会话结束时就断了,故障排查成本极高;
     * 更糟的是接入方可能据此设计"无人值守刷新",上线后才暴露。</p>
     *
     * @param scopes 请求给出的作用域列表,为 null 表示不修改(更新场景)
     * @throws BusinessException 含 {@code offline_access}
     */
    private void validateScopes(List<String> scopes) {
        if (scopes == null) {
            return;
        }
        for (String scope : scopes) {
            if (scope != null && OFFLINE_ACCESS_SCOPE.equalsIgnoreCase(scope.trim())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "平台不支持 offline_access:刷新令牌随会话结束失效,不提供离线令牌语义");
            }
        }
    }

    /**
     * 删除客户端。
     * <p>删除前检查下游引用:授权同意、在线会话、客户端角色与绑定该客户端的应用服务。
     * 有引用时拒绝删除并逐类给出条数——软删留下的悬空行会让"客户端标识重建后沿用旧同意"
     * 这类问题变成静默的安全缺口,而级联清理会误删仍在服务中的会话与角色授权,
     * 因此选择"先解除引用再删除"。客户端自身的作用域分配不属于下游引用,随客户端一并清理。</p>
     *
     * @param id 客户端主键
     * @throws BusinessException 客户端不存在,或仍被下游数据引用
     */
    @Override
    @Transactional
    public void delete(Long id) {
        requireClientInRealm(id);
        ClientReferenceCount references = clientManager.countReferences(id);
        if (references != null && references.blockingTotal() > 0) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, blockingReferenceMessage(references));
        }
        // 作用域分配是客户端自己的配置项,随客户端一起清理,不留悬空关联
        clientScopeManager.removeClientScopes(id);
        clientManager.remove(id);
    }

    /**
     * 变更客户端类型。
     * <p>类型决定令牌端点的认证方式(NONE 还是密钥凭证),不是普通属性:原位切换会让
     * 已签发的授权跨类型继续有效,机密性就取决于配置历史而不是当下配置。因此只在客户端
     * 尚未投入使用时放行。降级为公开客户端会清空密钥并强制 PKCE;升级为机密客户端
     * 现场签发新密钥,明文只在本次响应中返回。</p>
     *
     * @param id      客户端主键
     * @param request 目标类型
     * @return 变更后的客户端;升级为机密客户端时携带一次性明文密钥
     * @throws BusinessException 客户端不存在、不属于当前域、已投入使用,或与凭据模式冲突
     */
    @Override
    @Transactional
    public ClientResponse changeType(Long id, ClientTypeChangeRequest request) {
        // 1. 目标客户端必须在当前域
        Client client = requireClientInRealm(id);
        String target = request.clientType().trim().toUpperCase();
        // 2. 同类型视为幂等,不重发密钥
        if (target.equalsIgnoreCase(client.getClientType())) {
            return dtoConverter.toResponse(client);
        }
        // 3. 只有尚未投入使用的客户端允许改类型:切换会改变令牌端点认证方式,
        //    已签发的授权与正在跑的会话会跨类型继续有效,机密性就取决于配置历史
        ClientReferenceCount references = clientManager.countReferences(id);
        if (references != null && !references.neverUsed()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "该客户端已投入使用(存在客户端会话或授权同意记录),类型不可变更;"
                            + "请新建目标类型的客户端并把回调地址迁过去,再停用旧客户端");
        }
        // 4. 降级为公共客户端时不能保留凭据模式:该模式必须有机密客户端与服务账号
        if (TYPE_PUBLIC.equals(target)
                && clientConverter.grantTypesOf(client.getGrantTypes()).contains(GRANT_CLIENT_CREDENTIALS)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "该客户端已启用凭据模式(client_credentials),公共客户端无法持有服务账号;"
                            + "请先移除该授权类型与服务账号");
        }
        // 5. 公共客户端必须强制 PKCE;机密客户端沿用原有要求(空值按强制处理,列本身非空)
        Boolean requirePkce = TYPE_PUBLIC.equals(target) || !Boolean.FALSE.equals(client.getRequirePkce())
                ? Boolean.TRUE : Boolean.FALSE;
        String plainSecret = clientManager.changeType(id, target, requirePkce);
        // 6. 明文密钥只在本次响应中返回
        return dtoConverter.toResponse(clientManager.getById(id), plainSecret);
    }

    /**
     * 组装阻塞删除的引用说明,逐类给出条数与解除入口。
     * <p>只报总数等于让人自己猜去哪解除:四类引用分属四个管理页面,
     * 猜错的成本是一次无效的重试。</p>
     *
     * @param references 分类引用计数
     * @return 面向运维的提示文案
     */
    private static String blockingReferenceMessage(ClientReferenceCount references) {
        List<String> parts = new ArrayList<>();
        if (countOf(references.getConsentCount()) > 0) {
            parts.add("授权同意 " + references.getConsentCount() + " 条(在「授权同意」页撤销)");
        }
        if (countOf(references.getSessionCount()) > 0) {
            parts.add("在线会话 " + references.getSessionCount() + " 条(在「会话」页强制下线)");
        }
        if (countOf(references.getRoleCount()) > 0) {
            parts.add("客户端角色 " + references.getRoleCount() + " 条(在「角色」页删除)");
        }
        if (countOf(references.getAppCount()) > 0) {
            parts.add("应用绑定 " + references.getAppCount() + " 条(在「应用服务」里解除 SSO 客户端绑定)");
        }
        return "客户端仍被下游数据引用:" + String.join("、", parts) + ";解除后再删除";
    }

    /**
     * 计数空值按 0 处理。
     *
     * @param value 计数值,可为 null
     * @return 非空原值,空值返回 0
     */
    private static long countOf(Long value) {
        return value == null ? 0L : value;
    }

    @Override
    public ClientResponse rotateSecret(Long id) {
        // 1. 目标客户端必须在当前域
        Client client = requireClientInRealm(id);
        // 2. 只有机密客户端持有密钥
        if (!TYPE_CONFIDENTIAL.equalsIgnoreCase(client.getClientType())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "公共客户端不使用密钥");
        }
        // 3. 轮换并返回新明文密钥(仅本次)
        String plainSecret = clientManager.rotateSecret(id);
        return dtoConverter.toResponse(clientManager.getById(id), plainSecret);
    }

    /**
     * 当前调用者的身份域主键。
     *
     * @return 身份域主键
     */
    private Long currentRealmId() {
        return CurrentUserHolder.require().getRealmId();
    }

    /**
     * 当前调用者的身份域标识。
     *
     * @return 身份域标识
     */
    private String currentRealmKey() {
        return CurrentUserHolder.require().getRealmKey();
    }

    /**
     * 校验准入白名单与域级可用登录方式有交集。
     * <p>空白项忽略,未知登录方式直接拒绝;交集为空时拒绝保存。</p>
     *
     * @param allowedLoginTypes 准入登录方式编码
     * @param realmId           身份域主键
     * @throws BusinessException 存在未知登录方式,或白名单在本域均不可用
     */
    private void validateAdmissionWhitelist(List<String> allowedLoginTypes, Long realmId) {
        if (allowedLoginTypes == null || allowedLoginTypes.isEmpty()) {
            return;
        }
        Set<LoginMethod> requested = new LinkedHashSet<>();
        for (String code : allowedLoginTypes) {
            // 空白项在写侧归一化时会被丢弃,校验侧同样忽略,两边口径保持一致
            if (code == null || code.isBlank()) {
                continue;
            }
            LoginMethod method = LoginMethod.fromCode(code);
            if (method == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "未知的登录方式: " + code);
            }
            requested.add(method);
        }
        // 全是空白项时等同没有配置白名单,不做收敛校验
        if (requested.isEmpty()) {
            return;
        }
        // 库中只存规范大写码,非法值在 API 边界拒绝
        Set<LoginMethod> available = loginMethodCatalog.availableInRealm(realmId, currentRealmKey());
        boolean anyUsable = requested.stream().anyMatch(available::contains);
        if (!anyUsable) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "白名单里的登录方式在本域都不可用(域级未开启或未配置):"
                            + String.join(",", requested.stream().map(LoginMethod::code).toList())
                            + ",该客户端将无人能登录");
        }
    }

    /**
     * 读取属于当前域的客户端。
     *
     * @param id 客户端主键
     * @return 客户端
     * @throws BusinessException 客户端不存在或不属于当前域
     */
    private Client requireClientInRealm(Long id) {
        Client client = clientManager.getById(id);
        if (client == null || !currentRealmId().equals(client.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "客户端不存在: " + id);
        }
        return client;
    }

    /**
     * 校验凭据模式(client_credentials)配置。
     * <p>声明的服务账号必须是同域用户;凭据模式仅限机密客户端且必须绑定服务账号。
     * 服务账号的域归属校验独立于凭据模式开关。</p>
     *
     * @param clientType           客户端类型
     * @param grantTypes           生效的授权类型
     * @param serviceAccountUserId 生效的服务账号主键
     * @param realmId              身份域主键
     * @throws BusinessException 服务账号不存在或跨域,或凭据模式配置不合法
     */
    private void validateClientCredentials(String clientType, List<String> grantTypes,
                                           Long serviceAccountUserId, Long realmId) {
        // 1. 只要声明了服务账号,就必须是同域真实用户
        if (serviceAccountUserId != null) {
            User account = userManager.getById(serviceAccountUserId);
            if (account == null || !realmId.equals(account.getRealmId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND,
                        "服务账号不存在或不属于当前身份域: " + serviceAccountUserId);
            }
        }
        // 2. 未启用凭据模式时,其余约束不适用
        boolean wantsCredentials = grantTypes != null && grantTypes.contains(GRANT_CLIENT_CREDENTIALS);
        if (!wantsCredentials) {
            return;
        }
        if (!TYPE_CONFIDENTIAL.equalsIgnoreCase(clientType)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "凭据模式(client_credentials)仅支持机密客户端");
        }
        if (serviceAccountUserId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "凭据模式必须绑定服务账号主体(没有主体的令牌无法定位权限归属)");
        }
    }

    /**
     * 校验登出回跳与后通道登出地址的形态,要求为绝对 http(s) 地址。
     *
     * @param postLogoutRedirectUris 登出回跳地址
     * @param backchannelLogoutUri   后通道登出地址
     * @throws BusinessException 地址含通配符、片段或不是绝对 http(s) 地址
     */
    private void validateLogoutTargets(List<String> postLogoutRedirectUris, String backchannelLogoutUri) {
        if (postLogoutRedirectUris != null) {
            for (String uri : postLogoutRedirectUris) {
                requireAbsoluteHttpUri(uri, "登出回跳地址");
            }
        }
        if (backchannelLogoutUri != null && !backchannelLogoutUri.isBlank()) {
            requireAbsoluteHttpUri(backchannelLogoutUri, "后通道登出地址");
        }
    }

    /**
     * 校验回调地址:必须是绝对 http(s) 地址,且不含逗号。
     * <p>库内以逗号分隔存多个地址,含逗号的一条会被拆成两条,其中一条成为新的白名单项。</p>
     *
     * @param redirectUris 回调地址
     * @throws BusinessException 地址形态不合法
     */
    private void validateRedirectUris(List<String> redirectUris) {
        if (redirectUris == null) {
            return;
        }
        for (String uri : redirectUris) {
            requireAbsoluteHttpUri(uri, "回调地址");
        }
    }

    /**
     * 校验单个地址是无通配符、无片段的绝对 http(s) URI。
     *
     * @param uri   地址
     * @param label 地址用途,用于错误信息
     * @throws BusinessException 地址形态不合法
     */
    private void requireAbsoluteHttpUri(String uri, String label) {
        if (uri == null || uri.isBlank()) {
            return;
        }
        String value = uri.trim();
        if (value.contains("*")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, label + "不支持通配符: " + value);
        }
        if (value.contains("#")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, label + "不允许携带片段: " + value);
        }
        if (value.contains(",")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, label + "不允许包含英文逗号: " + value);
        }
        if (!(value.startsWith("https://") || value.startsWith("http://"))) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    label + "必须是完整的 http(s) 地址: " + value);
        }
    }
}
