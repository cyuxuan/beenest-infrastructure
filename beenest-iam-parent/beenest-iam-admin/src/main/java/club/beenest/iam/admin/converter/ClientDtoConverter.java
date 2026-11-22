package club.beenest.iam.admin.converter;

import club.beenest.iam.admin.dto.ClientCreateRequest;
import club.beenest.iam.admin.dto.ClientResponse;
import club.beenest.iam.admin.dto.ClientUpdateRequest;
import club.beenest.iam.core.model.SubjectMode;
import club.beenest.iam.persistence.converter.ClientConverter;
import club.beenest.iam.persistence.entity.Client;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 客户端管理端对象转换器。
 * <p>回调地址与 scope 在实体侧是逗号分隔文本,在接口侧是集合,
 * 拆分与拼接统一委托给持久层的 {@link ClientConverter}。</p>
 */
@Component
public class ClientDtoConverter {

    /** 客户端类型:公共(无密钥,强制 PKCE) */
    private static final String TYPE_PUBLIC = "PUBLIC";

    private final ClientConverter clientConverter;

    public ClientDtoConverter(ClientConverter clientConverter) {
        this.clientConverter = clientConverter;
    }

    /**
     * 客户端实体转响应,不携带密钥。
     *
     * @param client 客户端实体,可为 null
     * @return 客户端响应,入参为 null 时返回 null
     */
    public ClientResponse toResponse(Client client) {
        return toResponse(client, null);
    }

    /**
     * 客户端实体转响应,可附带一次性明文密钥。
     * <p>密钥密文不回显,明文仅在创建与轮换时由调用方传入。</p>
     *
     * @param client      客户端实体,可为 null
     * @param plainSecret 一次性明文密钥,可为 null
     * @return 客户端响应,入参实体为 null 时返回 null
     */
    public ClientResponse toResponse(Client client, String plainSecret) {
        if (client == null) {
            return null;
        }
        // 密钥已加密落库,读取场景不回显;仅创建/轮换时使用传入的明文
        return new ClientResponse(
                client.getId(),
                client.getRealmId(),
                client.getClientKey(),
                client.getClientName(),
                client.getClientType(),
                plainSecret,
                clientConverter.splitToSet(client.getRedirectUris()),
                clientConverter.splitToSet(client.getPostLogoutRedirectUris()),
                client.getBackchannelLogoutUri(),
                // 缺省按复用:与 DDL 默认值一致,不因历史空值改变行为
                !Boolean.FALSE.equals(client.getReuseRefreshTokens()),
                client.getSubjectMode(),
                clientConverter.splitToSet(client.getScopes()),
                client.getRequirePkce(),
                client.getRequireConsent(),
                client.getAccessTokenTtl(),
                client.getRefreshTokenTtl(),
                client.getEnabled(),
                client.getDescription(),
                client.getCreatedTime(),
                client.getUpdatedTime(),
                // 准入策略:白名单空集合表示不限制
                clientConverter.splitToSet(client.getAllowedLoginTypes()),
                Boolean.TRUE.equals(client.getRequireMfa()),
                clientConverter.grantTypesOf(client.getGrantTypes()),
                client.getServiceAccountUserId());
    }

    /**
     * 新建请求转实体。
     * <p>公共客户端强制开启 PKCE;授权类型缺省为 authorization_code + refresh_token;
     * 准入白名单在写侧归一化为规范大写码。</p>
     *
     * @param request 新建请求
     * @param realmId 身份域主键
     * @return 客户端实体
     */
    public Client toEntity(ClientCreateRequest request, Long realmId) {
        boolean isPublic = TYPE_PUBLIC.equalsIgnoreCase(request.clientType());
        Client client = new Client();
        client.setRealmId(realmId);
        client.setClientKey(request.clientKey());
        client.setClientName(request.clientName());
        client.setClientType(request.clientType().toUpperCase());
        client.setRedirectUris(clientConverter.toCommaSeparated(request.redirectUris()));
        client.setPostLogoutRedirectUris(clientConverter.toCommaSeparated(request.postLogoutRedirectUris()));
        client.setBackchannelLogoutUri(blankToNull(request.backchannelLogoutUri()));
        client.setReuseRefreshTokens(!Boolean.FALSE.equals(request.reuseRefreshTokens()));
        client.setSubjectMode(subjectModeCode(request.subjectMode()));
        client.setScopes(clientConverter.toCommaSeparated(request.scopes()));
        // 公共客户端无密钥可保,PKCE 是授权码防重放的必要手段,强制开启
        client.setRequirePkce(isPublic || Boolean.TRUE.equals(request.requirePkce()));
        // 授权同意默认关闭,显式开启才展示同意页
        client.setRequireConsent(Boolean.TRUE.equals(request.requireConsent()));
        client.setAccessTokenTtl(request.accessTokenTtl());
        client.setRefreshTokenTtl(request.refreshTokenTtl());
        client.setEnabled(true);
        client.setDescription(request.description());
        // 归一化在写侧:库里只存规范大写码,读侧保持不做任何加工
        client.setAllowedLoginTypes(clientConverter.toCommaSeparated(
                normalizeLoginTypes(request.allowedLoginTypes())));
        client.setRequireMfa(Boolean.TRUE.equals(request.requireMfa()));
        // 授权类型与服务账号主体由服务层校验:凭据模式要求机密客户端并绑定服务账号
        client.setGrantTypes(clientConverter.toCommaSeparated(
                request.grantTypes() == null || request.grantTypes().isEmpty()
                        ? List.of("authorization_code", "refresh_token")
                        : request.grantTypes()));
        client.setServiceAccountUserId(request.serviceAccountUserId());
        return client;
    }

    /**
     * 将更新请求中的非空字段写入实体,客户端标识与类型不可修改。
     * <p>登出地址、主体策略与准入白名单支持显式清空:传空集合或空串时写入空串,
     * 动态更新语句对 null 字段跳过更新,写空串使清空落库。</p>
     *
     * @param client  库中客户端实体
     * @param request 更新请求
     */
    public void applyUpdate(Client client, ClientUpdateRequest request) {
        if (request.clientName() != null) {
            client.setClientName(request.clientName());
        }
        if (request.redirectUris() != null) {
            client.setRedirectUris(clientConverter.toCommaSeparated(request.redirectUris()));
        }
        // 登出回跳地址:传空数组写空串,保证清空语义落库
        if (request.postLogoutRedirectUris() != null) {
            String joined = clientConverter.toCommaSeparated(request.postLogoutRedirectUris());
            client.setPostLogoutRedirectUris(joined == null ? "" : joined);
        }
        if (request.backchannelLogoutUri() != null) {
            client.setBackchannelLogoutUri(request.backchannelLogoutUri().trim());
        }
        if (request.reuseRefreshTokens() != null) {
            client.setReuseRefreshTokens(request.reuseRefreshTokens());
        }
        // 空串表示显式清空、回落到继承域策略,故写空串而不是 null
        if (request.subjectMode() != null) {
            client.setSubjectMode(request.subjectMode().trim().isEmpty()
                    ? "" : subjectModeCode(request.subjectMode()));
        }
        if (request.scopes() != null) {
            client.setScopes(clientConverter.toCommaSeparated(request.scopes()));
        }
        if (request.requirePkce() != null) {
            // 公共客户端不允许关闭 PKCE
            boolean isPublic = TYPE_PUBLIC.equalsIgnoreCase(client.getClientType());
            client.setRequirePkce(isPublic || request.requirePkce());
        }
        if (request.requireConsent() != null) {
            client.setRequireConsent(request.requireConsent());
        }
        if (request.accessTokenTtl() != null) {
            client.setAccessTokenTtl(request.accessTokenTtl());
        }
        if (request.refreshTokenTtl() != null) {
            client.setRefreshTokenTtl(request.refreshTokenTtl());
        }
        if (request.enabled() != null) {
            client.setEnabled(request.enabled());
        }
        if (request.description() != null) {
            client.setDescription(request.description());
        }
        // 准入白名单:传空数组写空串,保证清空语义落库
        if (request.allowedLoginTypes() != null) {
            String joined = clientConverter.toCommaSeparated(
                    normalizeLoginTypes(request.allowedLoginTypes()));
            client.setAllowedLoginTypes(joined == null ? "" : joined);
        }
        if (request.requireMfa() != null) {
            client.setRequireMfa(request.requireMfa());
        }
        // 授权类型:传空数组表示回落到默认组合
        if (request.grantTypes() != null) {
            client.setGrantTypes(request.grantTypes().isEmpty()
                    ? "authorization_code,refresh_token"
                    : clientConverter.toCommaSeparated(request.grantTypes()));
        }
        if (request.serviceAccountUserId() != null) {
            client.setServiceAccountUserId(request.serviceAccountUserId());
        }
    }

    /**
     * 空白字符串归一为 null,使库中空串与未配置不与 null 混淆。
     *
     * @param value 原始值
     * @return 去空白后的值,null 或空白时返回 null
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 主体标识策略转存储值,取大写枚举名。
     *
     * @param value 主体策略原始值
     * @return 枚举编码,null 或空白时返回 null,表示继承所属域
     */
    private static String subjectModeCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return SubjectMode.parse(value).code();
    }

    /**
     * 登录方式取值归一化:大写、去空白、去重。
     * <p>归一化放在写侧,库里只存规范大写码;非法值由服务层
     * {@code validateAdmissionWhitelist} 在 API 边界拒绝。</p>
     *
     * @param values 登录方式编码列表,可为 null
     * @return 归一化后的编码列表,空白项被剔除
     */
    private static List<String> normalizeLoginTypes(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.trim().toUpperCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }
}
