package club.beenest.iam.authz.token;

import club.beenest.iam.authz.authorization.ApplicationVisibilityService;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.IamClientSessionService;
import club.beenest.iam.authz.session.SessionIdHash;
import club.beenest.iam.authz.subject.IamSubjectResolver;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.RoleModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.core.spi.provider.PermissionProvider;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.RoleProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Group;
import club.beenest.iam.persistence.entity.IdentityProvider;
import club.beenest.iam.persistence.entity.Position;
import club.beenest.iam.persistence.entity.ProtocolMapper;
import club.beenest.iam.persistence.entity.UserFederatedIdentity;
import club.beenest.iam.persistence.entity.UserGroup;
import club.beenest.iam.persistence.manager.IamAppRoleMappingManager;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamGroupManager;
import club.beenest.iam.persistence.manager.IamPositionManager;
import club.beenest.iam.persistence.manager.IamProtocolMapperManager;
import club.beenest.iam.persistence.mapper.IdentityProviderMapper;
import club.beenest.iam.persistence.mapper.UserFederatedIdentityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * JWT 令牌定制。
 * <p>在 access token / id token 中注入业务声明:域标识、域角色、客户端角色、
 * 权限集、用户组、用户档案与令牌版本(供全端下线校验);
 * 并按协议映射器(iam_protocol_mapper)把用户属性/固定值映射为自定义 claim。</p>
 * <p>身份声明包含手机号与渠道绑定({@code phone_number}、{@code idp_identities}):
 * 应用以手机号归并多端账号、以渠道标识建立本地绑定,不必再回调 IAM 查询。</p>
 * <p>令牌授权声明按应用收敛档输出:SCOPED 只输出当前应用的权限、当前客户端的角色
 * 与应用角色;LEGACY_GLOBAL 保留迁移期的全域扁平集合。客户端未绑定应用时按默认拒绝
 * 策略只输出身份声明,避免全域权限经未登记客户端泄露。</p>
 * <p>访问令牌的 {@code aud} 指向绑定的应用(多端入口共享同一受众),资源服务器据此
 * 判定令牌是否签给本系统;ID Token 的 {@code aud} 按 OIDC 规范仍是客户端 id。</p>
 */
@Component
public class IamJwtTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private static final Logger log = LoggerFactory.getLogger(IamJwtTokenCustomizer.class);

    /** 令牌收敛档:按应用裁剪 */
    private static final String TOKEN_PROFILE_SCOPED = "SCOPED";
    /** 令牌收敛档:保留全域扁平权限(存量兼容) */
    private static final String TOKEN_PROFILE_LEGACY = "LEGACY_GLOBAL";
    /** 授权模式:角色映射 */
    private static final String AUTHZ_MAPPED = "MAPPED";
    /** 授权模式:应用自管 */
    private static final String AUTHZ_APP_MANAGED = "APP_MANAGED";
    /** 应用受众缺省前缀:未登记受众时按应用编码派生 */
    private static final String DEFAULT_AUDIENCE_PREFIX = "urn:iam:app:";

    private final RoleProvider roleProvider;
    private final ClientProvider clientProvider;
    private final PermissionProvider permissionProvider;
    private final IamGroupManager groupManager;
    private final IamProtocolMapperManager protocolMapperManager;
    private final IamPositionManager positionManager;
    private final IamApplicationServiceManager appServiceManager;
    private final IamAppRoleMappingManager appRoleMappingManager;
    private final IamClientSessionService clientSessionService;
    private final IamSubjectResolver subjectResolver;
    private final UserProvider userProvider;
    private final RealmProvider realmProvider;
    private final UserFederatedIdentityMapper federatedIdentityMapper;
    private final IdentityProviderMapper identityProviderMapper;
    private final ApplicationVisibilityService visibilityService;

    public IamJwtTokenCustomizer(RoleProvider roleProvider, ClientProvider clientProvider,
                                 PermissionProvider permissionProvider,
                                 IamGroupManager groupManager, IamProtocolMapperManager protocolMapperManager,
                                 IamPositionManager positionManager,
                                 IamApplicationServiceManager appServiceManager,
                                 IamAppRoleMappingManager appRoleMappingManager,
                                 IamClientSessionService clientSessionService,
                                 IamSubjectResolver subjectResolver,
                                 UserProvider userProvider,
                                 RealmProvider realmProvider,
                                 UserFederatedIdentityMapper federatedIdentityMapper,
                                 IdentityProviderMapper identityProviderMapper,
                                 ApplicationVisibilityService visibilityService) {
        this.roleProvider = roleProvider;
        this.clientProvider = clientProvider;
        this.permissionProvider = permissionProvider;
        this.groupManager = groupManager;
        this.protocolMapperManager = protocolMapperManager;
        this.positionManager = positionManager;
        this.appServiceManager = appServiceManager;
        this.appRoleMappingManager = appRoleMappingManager;
        this.clientSessionService = clientSessionService;
        this.subjectResolver = subjectResolver;
        this.userProvider = userProvider;
        this.realmProvider = realmProvider;
        this.federatedIdentityMapper = federatedIdentityMapper;
        this.identityProviderMapper = identityProviderMapper;
        this.visibilityService = visibilityService;
    }

    /**
     * 向正在构建的令牌写入业务声明。
     * <p>主体来自授权属性中的用户快照;客户端凭证模式无用户参与,主体是客户端绑定的服务账号。
     * 主体解析不到时不写任何声明。</p>
     * <p>两类令牌的声明范围不同:访问令牌携带调用接口所需的授权声明(权限码、应用角色、
     * 组织归属)与 {@code token_kind};ID Token 只携带身份与角色声明,不写权限码,
     * 并打上 {@code token_type=id} 标记。这样即使 ID Token 被当作 Bearer 令牌重放,
     * 解码环节也会直接拒绝,拿不到任何接口权限。</p>
     *
     * @param context 令牌编码上下文,声明直接写入其 {@code claims}
     */
    @Override
    public void customize(JwtEncodingContext context) {
        // 1. 主体:交互式流程取授权属性中的用户快照;
        //    凭据模式(机器对机器)没有用户参与,主体是客户端绑定的服务账号
        IamUserPrincipal user = resolvePrincipal(context);
        if (user == null) {
            return;
        }
        boolean idToken = isIdToken(context);
        // 2. 公共声明:域与档案
        JwtClaimsSet.Builder claims = context.getClaims();
        normalizeScopeClaim(claims);
        applySubjectClaim(context, user, claims);
        claims.claim("realm", user.getRealmKey());
        claims.claim("realm_id", user.getRealmId());
        claims.claim("uid", user.getUserId());
        claims.claim("username", user.getUsername());
        if (user.getNickname() != null) {
            claims.claim("nickname", user.getNickname());
        }
        // 身份档案与渠道绑定:资源服务器按手机号归并多端账号、按渠道标识补充绑定,
        // 不必每建一个应用就到 IAM 查一次
        applyProfileClaims(user, claims);
        applyLinkedIdentities(user, claims);
        claims.claim(IamTokenTypeValidator.CLAIM_TOKEN_TYPE,
                idToken ? IamTokenTypeValidator.TYPE_ID : IamTokenTypeValidator.TYPE_ACCESS);
        // 会话摘要:访问令牌与 ID Token 都写。登出后验签环节凭它拒绝该会话签出的令牌
        applySessionIdClaim(context, claims);
        // 3. ID Token 分支:只写角色与 sid,到此结束
        if (idToken) {
            applyIdTokenClaims(user, claims);
            applyProtocolMappers(context, user, claims);
            return;
        }
        // 4. 访问令牌:令牌版本供全端下线校验
        claims.claim("token_version", user.getTokenVersion());
        // 5. 授权声明:按绑定应用的令牌收敛档与授权模式裁剪,并标注令牌来源类别
        if (isClientCredentials(context)) {
            // 机器调用方:输出平台权限全集。机器对机器调用的是 IAM 自身的管理接口,
            // 而平台权限(app_service_id IS NULL)按设计被排除在应用令牌之外,
            // 若在此处也按应用收敛,管理 API 将永远无法凭令牌访问
            claims.claim(IamPlatformApiAuthorizationManager.CLAIM_TOKEN_KIND,
                    IamPlatformApiAuthorizationManager.KIND_MACHINE);
            applyMachineClaims(user, claims);
        } else {
            ApplicationService app = resolveApplication(user, context);
            if (app == null) {
                // 未绑定应用的客户端是平台自身的第一方客户端(控制台、运维脚本),
                // 只有身份声明、没有权限码,但允许访问 IAM 自身的自助接口
                claims.claim(IamPlatformApiAuthorizationManager.CLAIM_TOKEN_KIND,
                        IamPlatformApiAuthorizationManager.KIND_PLATFORM);
                log.warn("客户端 {} 未绑定应用服务,按默认拒绝策略只输出身份声明",
                        context.getRegisteredClient() == null ? "unknown" : context.getRegisteredClient().getClientId());
            } else {
                claims.claim(IamPlatformApiAuthorizationManager.CLAIM_TOKEN_KIND,
                        IamPlatformApiAuthorizationManager.KIND_APPLICATION);
                // 受众改绑应用:资源服务器按 aud 判定"令牌是不是签给本系统的",
                // 不再把客户端 id 当受众;同一应用可登记多个受众,一次全部写入
                claims.audience(audiencesOf(app));
                applyAuthorizationClaims(user, rolesOf(user), groupIdsOf(user), app,
                        resolveRegisteredClientDbId(context), claims);
            }
        }
        // 6. 协议映射器:用户属性/固定值 → 自定义 claim(域级 + 客户端级)
        applyProtocolMappers(context, user, claims);
    }

    /**
     * 判断当前构建的是否为 ID Token。
     *
     * @param context 令牌编码上下文
     * @return true 表示令牌类型为 id_token
     */
    private static boolean isIdToken(JwtEncodingContext context) {
        return context.getTokenType() != null
                && OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue());
    }

    /**
     * 写入 ID Token 专属的角色声明。
     * <p>角色用于应用侧展示与界面裁剪,不参与接口鉴权,故保留在 ID Token;
     * 权限码是接口调用的唯一依据,只写进访问令牌。</p>
     *
     * @param user   用户主体快照
     * @param claims 令牌声明构建器
     */
    private void applyIdTokenClaims(IamUserPrincipal user, JwtClaimsSet.Builder claims) {
        List<RoleModel> roles = rolesOf(user);
        List<String> realmRoles = roles.stream()
                .filter(role -> role.containerType() == RoleModel.ContainerType.REALM)
                .map(RoleModel::roleCode)
                .toList();
        if (!realmRoles.isEmpty()) {
            claims.claim("realm_roles", realmRoles);
        }
        List<String> clientRoles = roles.stream()
                .filter(role -> role.containerType() == RoleModel.ContainerType.CLIENT
                        || role.containerType() == RoleModel.ContainerType.APP)
                .map(RoleModel::roleCode)
                .toList();
        if (!clientRoles.isEmpty()) {
            claims.claim("client_roles", clientRoles);
        }
    }

    /**
     * 解析令牌主体。
     * <p>交互式流程取授权属性中的用户快照(Principal 属性)。</p>
     * <p>凭据模式下协议栈只放入客户端认证主体、没有用户,主体取客户端绑定的服务账号;
     * 客户端未绑定服务账号时无权限归属,不允许使用凭据模式
     * (见 {@code IamRegisteredClientRepository})。</p>
     *
     * @param context 令牌编码上下文
     * @return 用户主体快照;无法解析时返回 null
     */
    private IamUserPrincipal resolvePrincipal(JwtEncodingContext context) {
        Object attributePrincipal = context.getAuthorization() == null
                ? null : context.getAuthorization().getAttribute(Principal.class.getName());
        if (attributePrincipal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof IamUserPrincipal user) {
            return user;
        }
        if (isClientCredentials(context)) {
            return resolveServiceAccount(context);
        }
        return null;
    }

    /**
     * 判断当前授权是否为凭据模式(机器对机器)。
     *
     * @param context 令牌编码上下文
     * @return true 表示授权类型为 client_credentials
     */
    private static boolean isClientCredentials(JwtEncodingContext context) {
        return context.getAuthorizationGrantType() != null
                && AuthorizationGrantType.CLIENT_CREDENTIALS.getValue()
                .equals(context.getAuthorizationGrantType().getValue());
    }

    /**
     * 凭据模式的主体:客户端绑定的服务账号。
     * <p>服务账号是机器身份在用户体系里的投影,有角色、有权限,不能交互式登录。</p>
     *
     * @param context 令牌编码上下文
     * @return 服务账号主体快照;客户端、服务账号、域缺失或状态不可用时返回 null
     */
    private IamUserPrincipal resolveServiceAccount(JwtEncodingContext context) {
        Long clientDbId = resolveRegisteredClientDbId(context);
        if (clientDbId == null) {
            return null;
        }
        ClientModel client = clientProvider.findById(clientDbId).orElse(null);
        if (client == null || client.serviceAccountUserId() == null) {
            return null;
        }
        UserModel user = userProvider.findById(client.serviceAccountUserId()).orElse(null);
        if (user == null || user.status() != 1) {
            log.warn("客户端 {} 的服务账号不存在或已停用,凭据模式令牌不输出权限", client.clientKey());
            return null;
        }
        RealmModel realm = realmProvider.findById(client.realmId()).orElse(null);
        if (realm == null || !realm.enabled()) {
            return null;
        }
        long epoch = permissionProvider.currentPermissionEpoch(realm.id());
        List<String> permissionCodes = List.copyOf(
                permissionProvider.computePermissionCodes(realm.id(), user.id()));
        return new IamUserPrincipal(user.id(), realm.id(), realm.realmKey(),
                user.username(), user.nickname(), user.tokenVersion(),
                permissionCodes, user.attributes(), epoch);
    }

    /**
     * 凭据模式的授权声明:服务账号的权限全集加客户端角色。
     * <p>不按应用收敛:机器调用方访问 IAM 自身管理接口,平台权限正是其所需。</p>
     *
     * @param user   服务账号主体
     * @param claims 令牌声明构建器
     */
    private void applyMachineClaims(IamUserPrincipal user, JwtClaimsSet.Builder claims) {
        if (!user.getAuthorities().isEmpty()) {
            claims.claim("permissions", user.getAuthorities().stream().sorted().toList());
        }
        List<String> clientRoles = rolesOf(user).stream()
                .filter(role -> role.containerType() == RoleModel.ContainerType.CLIENT)
                .map(RoleModel::roleCode)
                .toList();
        if (!clientRoles.isEmpty()) {
            claims.claim("client_roles", clientRoles);
        }
    }

    /**
     * 覆盖 sub 并补 preferred_username。
     * <p>SAS 默认把 sub 写成登录名;登录名可变,作为跨系统用户锚点不合格,按域策略与
     * 客户端覆盖改写为不可变用户主键(可配置)。生成器在调用定制器前已写入默认 sub,
     * 此处再写一次即覆盖。</p>
     * <p>同时固定写 preferred_username:sub 为 ID 后应用需要可读展示名(OIDC 标准做法);
     * userinfo 端点按标准声明从 ID Token 取值,profile scope 下直接返回。</p>
     *
     * @param context 令牌编码上下文
     * @param user    用户主体快照
     * @param claims  令牌声明构建器
     */
    private void applySubjectClaim(JwtEncodingContext context, IamUserPrincipal user,
                                   JwtClaimsSet.Builder claims) {
        String clientKey = context.getRegisteredClient() == null
                ? null : context.getRegisteredClient().getClientId();
        String subject = subjectResolver.resolve(user, clientKey);
        if (subject != null && !subject.isBlank()) {
            claims.subject(subject);
        }
        if (user.getUsername() != null) {
            claims.claim("preferred_username", user.getUsername());
        }
    }

    /**
     * 写入会话摘要 {@code sid},值为会话标识的 SHA-256 摘要,不含会话票据本身。
     * <p>ID Token 用它做 RP-Initiated Logout 的会话锚点;访问令牌用它做登出后的即时失效判定。
     * 机器对机器调用没有会话,不写该声明。</p>
     *
     * @param context 令牌编码上下文
     * @param claims  令牌声明构建器
     */
    private void applySessionIdClaim(JwtEncodingContext context, JwtClaimsSet.Builder claims) {
        if (context.getAuthorization() == null) {
            return;
        }
        String sid = clientSessionService.resolveSessionId(context.getAuthorization().getId());
        String sidHash = SessionIdHash.of(sid);
        if (sidHash != null) {
            claims.claim(IamTokenBlocklistValidator.CLAIM_SID, sidHash);
        }
    }

    /**
     * 写入手机号与邮箱。
     * <p>两者是应用侧归并多端账号、补全业务档案的识别键。取库中当前值而不是登录快照:
     * 管理员改过的手机号,下一次签发令牌即可生效。</p>
     *
     * @param user   用户主体快照
     * @param claims 令牌声明构建器
     */
    private void applyProfileClaims(IamUserPrincipal user, JwtClaimsSet.Builder claims) {
        if (user.getUserId() == null) {
            return;
        }
        UserModel profile = userProvider.findById(user.getUserId()).orElse(null);
        if (profile == null) {
            return;
        }
        if (profile.phone() != null && !profile.phone().isBlank()) {
            claims.claim("phone_number", profile.phone());
        }
        if (profile.email() != null && !profile.email().isBlank()) {
            claims.claim("email", profile.email());
        }
    }

    /**
     * 写入用户已绑定的渠道身份。
     * <p>键为提供方类型(如 {@code wechat-mini}),值为该渠道的外部标识。应用按手机号命中
     * 本地档案后,用这里的标识建立渠道绑定,换端登录不再重复建档。</p>
     *
     * @param user   用户主体快照
     * @param claims 令牌声明构建器
     */
    private void applyLinkedIdentities(IamUserPrincipal user, JwtClaimsSet.Builder claims) {
        if (user.getUserId() == null || user.getRealmId() == null) {
            return;
        }
        List<UserFederatedIdentity> links = federatedIdentityMapper.selectByUserId(user.getUserId());
        if (links.isEmpty()) {
            return;
        }
        Map<Long, String> providerTypes = new LinkedHashMap<>();
        for (IdentityProvider provider : identityProviderMapper.selectByRealmId(user.getRealmId())) {
            if (provider.getId() != null && provider.getProviderId() != null
                    && !provider.getProviderId().isBlank()) {
                providerTypes.putIfAbsent(provider.getId(), provider.getProviderId());
            }
        }
        Map<String, String> identities = new LinkedHashMap<>();
        for (UserFederatedIdentity link : links) {
            if (!user.getRealmId().equals(link.getRealmId())
                    || link.getExternalId() == null || link.getExternalId().isBlank()) {
                continue;
            }
            String providerType = providerTypes.get(link.getIdpId());
            if (providerType != null) {
                identities.putIfAbsent(providerType, link.getExternalId());
            }
        }
        if (!identities.isEmpty()) {
            claims.claim("idp_identities", identities);
        }
    }

    /**
     * 用户所属组的编码集合,按主键排序保证稳定。
     *
     * @param realmId  身份域主键
     * @param groupIds 用户所属组主键
     * @return 组编码列表;groupIds 为空时返回空列表
     */
    private List<String> groupCodes(Long realmId, List<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return List.of();
        }
        return groupManager.listByRealm(realmId).stream()
                .filter(g -> groupIds.contains(g.getId()))
                .map(g -> g.getGroupCode())
                .sorted()
                .toList();
    }

    /**
     * 用户有效角色,含直授与组继承。
     *
     * @param user 用户主体快照
     * @return 角色列表
     */
    private List<RoleModel> rolesOf(IamUserPrincipal user) {
        return roleProvider.findRolesByUserId(user.getRealmId(), user.getUserId());
    }

    /**
     * 用户所属组主键。
     *
     * @param user 用户主体快照
     * @return 组主键列表
     */
    private List<Long> groupIdsOf(IamUserPrincipal user) {
        return groupManager.listGroupIdsByUser(user.getUserId());
    }

    /**
     * 写入主部门与任职岗位。
     * <p>只输出主部门:兼职部门已在 groups 全量给出,再塞进 dept 会让部门归属产生歧义;
     * 岗位取全部任职岗位。</p>
     * <p>跨域关联一律忽略:关联行带 realm_id,部门与岗位实体仍按域复核一次,令牌里的部门
     * 编码会被业务系统当作归属依据,串域即越权取数。</p>
     *
     * @param user   用户主体快照
     * @param claims 令牌声明构建器
     */
    private void applyOrganizationClaims(IamUserPrincipal user, JwtClaimsSet.Builder claims) {
        List<UserGroup> relations = groupManager.listRelationsByUserId(user.getUserId());
        if (relations.isEmpty()) {
            return;
        }
        UserGroup primary = relations.stream()
                .filter(relation -> Boolean.TRUE.equals(relation.getIsPrimary()))
                .filter(relation -> user.getRealmId().equals(relation.getRealmId()))
                .findFirst()
                .orElse(null);
        if (primary != null) {
            Group group = groupManager.getById(primary.getGroupId());
            if (group != null && user.getRealmId().equals(group.getRealmId())) {
                claims.claim("dept", group.getGroupCode());
                claims.claim("dept_name", group.getGroupName());
            }
        }
        List<Long> positionIds = relations.stream()
                .filter(relation -> user.getRealmId().equals(relation.getRealmId()))
                .map(UserGroup::getPositionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!positionIds.isEmpty()) {
            List<String> positionCodes = positionManager.listByIds(positionIds).stream()
                    .filter(position -> user.getRealmId().equals(position.getRealmId()))
                    .map(Position::getPositionCode)
                    .sorted()
                    .toList();
            if (!positionCodes.isEmpty()) {
                claims.claim("positions", positionCodes);
            }
        }
    }

    /**
     * 写入应用作用域内的授权声明。
     * <p>SCOPED 档下权限只含当前应用命名空间、client_roles 只含当前客户端角色、
     * APP 角色单独写入 app_roles;LEGACY_GLOBAL 保留全域扁平集合兼容存量应用。</p>
     *
     * @param user       用户主体快照
     * @param roles      用户有效角色
     * @param groupIds   用户所属组主键
     * @param app        当前客户端绑定的应用服务
     * @param clientDbId 注册客户端主键
     * @param claims     令牌声明构建器
     */
    private void applyAuthorizationClaims(IamUserPrincipal user, List<RoleModel> roles, List<Long> groupIds,
                                          ApplicationService app, Long clientDbId,
                                          JwtClaimsSet.Builder claims) {
        // 只有显式声明 LEGACY_GLOBAL 才走全域权限;其余值(含拼错、空值)一律按收敛档处理,
        // 配置错误不应把全域权限码发给业务应用
        boolean scoped = !TOKEN_PROFILE_LEGACY.equalsIgnoreCase(app.getTokenProfile());
        boolean mapped = AUTHZ_MAPPED.equals(app.getAuthorizationMode());
        boolean appManaged = AUTHZ_APP_MANAGED.equals(app.getAuthorizationMode());
        // 1. 域角色在两个收敛档下都保留
        List<String> realmRoles = roles.stream()
                .filter(role -> role.containerType() == RoleModel.ContainerType.REALM)
                .map(RoleModel::roleCode)
                .toList();
        if (!realmRoles.isEmpty()) {
            claims.claim("realm_roles", realmRoles);
        }
        // 2. 客户端角色:SCOPED 只取当前客户端;LEGACY 保留全部 APP/CLIENT 角色
        List<String> clientRoles = scoped
                ? roles.stream()
                    .filter(role -> role.containerType() == RoleModel.ContainerType.CLIENT)
                    .filter(role -> clientDbId != null && clientDbId.equals(role.clientId()))
                    .map(RoleModel::roleCode)
                    .toList()
                : roles.stream()
                    .filter(role -> role.containerType() == RoleModel.ContainerType.APP
                            || role.containerType() == RoleModel.ContainerType.CLIENT)
                    .map(RoleModel::roleCode)
                    .toList();
        if (!clientRoles.isEmpty()) {
            claims.claim("client_roles", clientRoles);
        }
        // 3. 用户组(组织维度,供业务侧按部门授权)
        List<String> groupCodes = groupCodes(user.getRealmId(), groupIds);
        if (!groupCodes.isEmpty()) {
            claims.claim("groups", groupCodes);
        }
        // 3.5 主部门与任职岗位:审批路由、数据归属、花名册都按这两个字段取,
        //     业务系统不必再回到 IAM 查一次组织关系
        applyOrganizationClaims(user, claims);
        // 4. 应用角色:MAPPED 输出映射结果;IAM_MANAGED + SCOPED 输出本应用角色
        if (mapped) {
            Set<String> appRoleCodes = new TreeSet<>(appRoleMappingManager.resolveTargetRoleCodes(
                    user.getRealmId(), app.getId(), groupIds,
                    roles.stream().map(RoleModel::id).toList()));
            // 直授的应用角色本身就是本应用的角色码,是终态授权而非映射来源;
            // 只在 SCOPED 并入,存量 LEGACY 应用维持原有声明不变
            if (scoped) {
                roles.stream()
                        .filter(role -> role.containerType() == RoleModel.ContainerType.APP)
                        .filter(role -> app.getId().equals(role.appServiceId()))
                        .map(RoleModel::roleCode)
                        .forEach(appRoleCodes::add);
            }
            if (!appRoleCodes.isEmpty()) {
                claims.claim("app_roles", List.copyOf(appRoleCodes));
            }
        } else if (scoped && !appManaged) {
            List<String> appRoles = roles.stream()
                    .filter(role -> role.containerType() == RoleModel.ContainerType.APP)
                    .filter(role -> app.getId().equals(role.appServiceId()))
                    .map(RoleModel::roleCode)
                    .sorted()
                    .toList();
            if (!appRoles.isEmpty()) {
                claims.claim("app_roles", appRoles);
            }
        }
        // 5. 权限:SCOPED 只算当前应用权限,APP_MANAGED 不输出;
        //    LEGACY 按库中当前授权关系计算,不用登录快照——快照是签发时的旧值,
        //    管理员撤角色后旧刷新令牌换出的访问令牌不应再带原权限码
        if (scoped) {
            if (!appManaged) {
                Set<String> appPermissions = permissionProvider.computeAppPermissionCodes(
                        user.getRealmId(), user.getUserId(), app.getId());
                if (!appPermissions.isEmpty()) {
                    claims.claim("permissions", appPermissions.stream().sorted().toList());
                }
            }
        } else {
            Set<String> permissions = permissionProvider.computePermissionCodes(
                    user.getRealmId(), user.getUserId());
            if (!permissions.isEmpty()) {
                claims.claim("permissions", permissions.stream().sorted().toList());
            }
        }
    }

    /**
     * 取当前客户端对应的应用服务。
     * <p>优先按客户端绑定查找;未绑定时回退到用户授权:若用户恰好被授权一个应用
     * (经角色、直授或组),自动使用该应用,避免管理员必须配置客户端绑定。</p>
     *
     * @param user    用户主体快照
     * @param context 令牌编码上下文
     * @return 应用服务;无法确定时返回 null
     */
    private ApplicationService resolveApplication(IamUserPrincipal user, JwtEncodingContext context) {
        Long clientDbId = resolveRegisteredClientDbId(context);
        if (clientDbId != null) {
            ApplicationService bound = appServiceManager.getBySsoClientPk(user.getRealmId(), clientDbId);
            if (bound != null) {
                return bound;
            }
        }
        // 回退:客户端未绑定应用时,若用户恰好被授权一个应用,自动使用该应用
        List<ApplicationService> granted = visibilityService.grantedApps(user.getRealmId(), user.getUserId());
        if (granted.size() == 1) {
            log.debug("客户端未绑定应用,但用户恰好被授权一个应用,自动使用: userId={}, appCode={}",
                    user.getUserId(), granted.get(0).getAppCode());
            return granted.get(0);
        }
        return null;
    }

    /**
     * 取应用受众列表:显式登记优先,未登记时按应用编码派生。
     * <p>库内以英文逗号分隔存储,这里拆成列表写入 {@code aud}。多个受众用于
     * 同一个令牌被多个资源服务器接受的场景,例如网关与后端各校验自己的标识。</p>
     *
     * @param app 绑定的应用服务
     * @return 写入访问令牌 {@code aud} 的受众列表,至少一个元素
     */
    private static List<String> audiencesOf(ApplicationService app) {
        String configured = app.getAudience();
        if (configured != null && !configured.isBlank()) {
            List<String> audiences = java.util.Arrays.stream(configured.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .distinct()
                    .toList();
            if (!audiences.isEmpty()) {
                return audiences;
            }
        }
        return List.of(DEFAULT_AUDIENCE_PREFIX + app.getAppCode());
    }

    /**
     * 取注册客户端主键。
     *
     * @param context 令牌编码上下文
     * @return 客户端主键;上下文缺失或主键非数字(外部客户端)时返回 null
     */
    private Long resolveRegisteredClientDbId(JwtEncodingContext context) {
        if (context.getRegisteredClient() == null) {
            return null;
        }
        try {
            return Long.parseLong(context.getRegisteredClient().getId());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 应用协议映射器,把用户属性或固定值映射为自定义 claim。
     *
     * @param context 令牌编码上下文
     * @param user    用户主体快照
     * @param claims  令牌声明构建器
     */
    private void applyProtocolMappers(JwtEncodingContext context, IamUserPrincipal user,
                                      JwtClaimsSet.Builder claims) {
        Long clientDbId = resolveClientDbId(user, context);
        List<ProtocolMapper> mappers = protocolMapperManager.listEffective(user.getRealmId(), clientDbId);
        if (mappers.isEmpty()) {
            return;
        }
        Map<String, String> attributes = user.getAttributes();
        for (ProtocolMapper mapper : mappers) {
            Object value = null;
            if (mapper.getUserAttribute() != null && !mapper.getUserAttribute().isBlank()) {
                value = attributes.get(mapper.getUserAttribute());
            } else if (mapper.getClaimTemplate() != null) {
                value = mapper.getClaimTemplate();
            }
            if (value == null) {
                continue;
            }
            claims.claim(mapper.getClaimName(), convertClaimType(value, mapper.getClaimType()));
        }
    }

    /**
     * 按 claim 类型转换映射值。
     *
     * @param value     待转换的值
     * @param claimType 目标类型(STRING/NUMBER/BOOLEAN/JSON),大小写不敏感
     * @return 转换后的 claim 值;类型未知或解析失败时返回原字符串
     */
    private static Object convertClaimType(Object value, String claimType) {
        String type = claimType == null ? "STRING" : claimType.toUpperCase();
        String text = String.valueOf(value);
        return switch (type) {
            case "NUMBER" -> parseLongOrOriginal(text);
            case "BOOLEAN" -> Boolean.parseBoolean(text);
            case "JSON" -> parseJsonOrOriginal(text);
            default -> text;
        };
    }

    /**
     * 尝试把文本解析为 Long。
     *
     * @param text 待解析文本
     * @return 解析成功返回 Long,失败返回原文本
     */
    private static Object parseLongOrOriginal(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return text;
        }
    }

    /**
     * 尝试把文本解析为 JSON 对象。
     *
     * @param text 待解析文本
     * @return 解析成功返回反序列化对象,失败返回原文本
     */
    private static Object parseJsonOrOriginal(String text) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(text, Object.class);
        } catch (Exception e) {
            return text;
        }
    }

    /**
     * 将 scope 声明归一化为空格分隔字符串。
     * <p>RFC 9068 规定 scope 是字符串;编码器写成数组时,非 Spring 生态的资源服务器
     * (网关、APM、第三方 SDK)按字符串解析会直接失配。</p>
     *
     * @param claims 令牌声明构建器
     */
    private static void normalizeScopeClaim(JwtClaimsSet.Builder claims) {
        Object scopeClaim = claims.build().getClaim("scope");
        if (scopeClaim instanceof java.util.Collection<?> values) {
            claims.claim("scope", values.stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(" ")));
        }
    }

    /**
     * 由授权上下文解析客户端主键,域级映射不依赖客户端。
     *
     * @param user    用户主体快照
     * @param context 令牌编码上下文
     * @return 客户端主键;授权缺失、注册客户端 id 为空或查不到时返回 null
     */
    private Long resolveClientDbId(IamUserPrincipal user, JwtEncodingContext context) {
        if (context.getAuthorization() == null) {
            return null;
        }
        String registeredClientId = context.getAuthorization().getRegisteredClientId();
        if (registeredClientId == null) {
            return null;
        }
        // registeredClientId 是客户端主键的字符串形式(见 IamRegisteredClientRepository),
        // 按 client_key 查会查不到,或串到另一个恰好同名的客户端
        try {
            return clientProvider.findById(Long.valueOf(registeredClientId))
                    .map(ClientModel::id)
                    .orElse(null);
        } catch (NumberFormatException e) {
            log.warn("注册客户端标识不是主键: {}", registeredClientId);
            return null;
        }
    }
}
