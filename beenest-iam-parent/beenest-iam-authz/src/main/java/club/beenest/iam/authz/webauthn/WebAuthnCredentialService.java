package club.beenest.iam.authz.webauthn;

import club.beenest.iam.authz.notification.SecurityAlertService;
import club.beenest.iam.authz.rest.dto.WebAuthnAssertionRequest;
import club.beenest.iam.authz.rest.dto.WebAuthnCredentialResponse;
import club.beenest.iam.authz.rest.dto.WebAuthnRegisterRequest;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.entity.WebAuthnCredential;
import club.beenest.iam.persistence.manager.IamWebAuthnCredentialManager;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.VerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Passkey 凭证服务:注册、断言校验与凭证管理。
 * <p>密码学校验全部交给协议库,本类负责三件库不负责的事:</p>
 * <ul>
 *   <li>挑战的签发与一次性消费(见 {@link WebAuthnChallengeService});</li>
 *   <li>把库还原出的凭证与本地账号对应起来,并拒绝跨账号使用;</li>
 *   <li>签名计数器的单调性:计数器回退说明同一把密钥在两台设备上同时存在,
 *       属克隆特征,按失窃处理、判定校验失败。</li>
 * </ul>
 * <p>登录选项接口对已绑定凭证的账号返回 {@code allowCredentials},
 * 对未绑定或不存在的账号返回空数组;两种响应结构一致,仅数组长度不同。
 * 浏览器需要该列表选定密钥,账号是否绑定过 Passkey 由此可被探测;
 * 账号存在性本身仍由口令登录保持不可区分。</p>
 */
@Service
public class WebAuthnCredentialService {

    private static final Logger log = LoggerFactory.getLogger(WebAuthnCredentialService.class);

    /** 支持的签名算法 */
    private static final List<PublicKeyCredentialParameters> PUB_KEY_CRED_PARAMS = List.of(
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY,
                    COSEAlgorithmIdentifier.ES256),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY,
                    COSEAlgorithmIdentifier.RS256));

    /** 未命名凭证的默认名称前缀 */
    private static final String DEFAULT_NAME_PREFIX = "Passkey ";

    private final WebAuthnProperties properties;
    private final WebAuthnChallengeService challengeService;
    private final WebAuthnOptionsFactory optionsFactory;
    private final IamWebAuthnCredentialManager credentialManager;
    private final UserProvider userProvider;
    private final WebAuthnManager webAuthnManager;
    private final AttestedCredentialDataConverter credentialDataConverter;
    private final SecurityAlertService securityAlertService;

    /**
     * 构造器注入。
     *
     * @param properties              Passkey 配置
     * @param challengeService        挑战票据服务
     * @param optionsFactory          选项构造器
     * @param credentialManager       凭证存储
     * @param userProvider            用户查询
     * @param webAuthnManager         协议校验器
     * @param credentialDataConverter 凭证数据转换器
     * @param securityAlertService    凭证变更提醒
     */
    public WebAuthnCredentialService(WebAuthnProperties properties,
                                     WebAuthnChallengeService challengeService,
                                     WebAuthnOptionsFactory optionsFactory,
                                     IamWebAuthnCredentialManager credentialManager,
                                     UserProvider userProvider,
                                     WebAuthnManager webAuthnManager,
                                     AttestedCredentialDataConverter credentialDataConverter,
                                     SecurityAlertService securityAlertService) {
        this.properties = properties;
        this.challengeService = challengeService;
        this.optionsFactory = optionsFactory;
        this.credentialManager = credentialManager;
        this.userProvider = userProvider;
        this.webAuthnManager = webAuthnManager;
        this.credentialDataConverter = credentialDataConverter;
        this.securityAlertService = securityAlertService;
    }

    /**
     * Passkey 能力是否可用(未启用时各入口直接报错而不是让浏览器侧失败)。
     *
     * @return true 表示已启用且依赖方配置完整
     */
    public boolean isAvailable() {
        return properties.isAvailable();
    }

    // ===== 注册 =====

    /**
     * 开始注册:签发挑战并返回创建选项。
     *
     * @param realmKey 身份域标识
     * @param realmId  身份域主键
     * @param userId   用户主键
     * @param username 登录名
     * @return 浏览器创建选项
     * @throws BusinessException 能力未启用,或该用户凭证数量已达上限
     */
    public Map<String, Object> beginRegistration(String realmKey, Long realmId, Long userId, String username) {
        requireAvailable();
        List<WebAuthnCredential> existing = credentialManager.listByUserId(realmId, userId);
        if (existing.size() >= properties.getMaxCredentialsPerUser()) {
            throw new BusinessException(ErrorCode.WEBAUTHN_CREDENTIAL_LIMIT);
        }
        byte[] challenge = challengeService.issueRegistration(realmKey, userId);
        byte[] userHandle = resolveUserHandle(realmId, userId, existing);
        String nickname = userProvider.findById(userId).map(UserModel::nickname).orElse(null);
        return optionsFactory.registrationOptions(challenge, userHandle, username, nickname, existing);
    }

    /**
     * 完成注册:校验证明对象并落库。
     *
     * @param realmKey 身份域标识
     * @param realmId  身份域主键
     * @param userId   用户主键
     * @param request  浏览器返回的凭证数据
     * @return 新建凭证的对外视图
     * @throws BusinessException 能力未启用、挑战过期、报文不合法、凭证已存在或校验失败
     */
    @Transactional
    public WebAuthnCredentialResponse finishRegistration(String realmKey, Long realmId, Long userId,
                                                         WebAuthnRegisterRequest request) {
        requireAvailable();
        byte[] challenge = challengeService.consumeRegistration(realmKey, userId);
        if (challenge == null) {
            throw new BusinessException(ErrorCode.WEBAUTHN_CHALLENGE_INVALID);
        }
        RegistrationData data = verifyAttestation(challenge, request);

        AuthenticatorData<?> authenticatorData = data.getAttestationObject().getAuthenticatorData();
        AttestedCredentialData attested = authenticatorData.getAttestedCredentialData();
        if (attested == null) {
            // 没有凭证公钥的证明对象无法用于后续断言,收下只会变成一把永远验不过的钥匙
            throw new BusinessException(ErrorCode.WEBAUTHN_VERIFICATION_FAILED);
        }
        String credentialId = WebAuthnCodec.encode(attested.getCredentialId());
        if (credentialManager.findByCredentialId(credentialId) != null) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "该密钥已绑定过账号");
        }

        List<WebAuthnCredential> existing = credentialManager.listByUserId(realmId, userId);
        if (existing.size() >= properties.getMaxCredentialsPerUser()) {
            throw new BusinessException(ErrorCode.WEBAUTHN_CREDENTIAL_LIMIT);
        }

        WebAuthnCredential credential = new WebAuthnCredential();
        credential.setRealmId(realmId);
        credential.setUserId(userId);
        credential.setCredentialId(credentialId);
        credential.setCredentialData(WebAuthnCodec.encode(credentialDataConverter.convert(attested)));
        credential.setUserHandle(WebAuthnCodec.encode(resolveUserHandle(realmId, userId, existing)));
        credential.setAaguid(aaguidOf(attested));
        credential.setSignCount(authenticatorData.getSignCount());
        credential.setTransports(joinTransports(request.transports()));
        credential.setDisplayName(displayNameOrDefault(request.displayName(), existing.size()));
        credential.setBackupEligible(authenticatorData.isFlagBE());
        credential.setBackupState(authenticatorData.isFlagBS());
        credentialManager.create(credential);
        log.info("Passkey 绑定成功: realm={}, userId={}, credentialId={}", realmKey, userId, credentialId);
        securityAlertService.alertCredentialChanged(realmId, userId, "已添加 Passkey",
                "你的账号刚刚添加了一个 Passkey(免口令登录凭证)。请确认是你本人的设备。");
        return toResponse(credential);
    }

    // ===== 登录 =====

    /**
     * 开始登录:签发挑战并返回请求选项。
     * <p>账号为空表示无用户名登录(由认证器自行选择密钥);配置关闭无用户名登录时拒绝。</p>
     *
     * @param realmKey 身份域标识
     * @param realmId  身份域主键
     * @param account  登录名;可为空
     * @return 浏览器请求选项
     * @throws BusinessException 能力未启用,或未开启无用户名登录却未提供账号
     */
    public Map<String, Object> beginAuthentication(String realmKey, Long realmId, String account) {
        requireAvailable();
        boolean usernameless = account == null || account.isBlank();
        if (usernameless) {
            if (!properties.isAllowUsernameless()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "请输入账号");
            }
            byte[] challenge = challengeService.issueAuthentication(realmKey, null);
            return optionsFactory.authenticationOptions(challenge, List.of());
        }
        Long userId = realmId == null ? null
                : userProvider.findByUsername(realmId, account.trim()).map(UserModel::id).orElse(null);
        List<WebAuthnCredential> credentials = userId == null
                ? List.of() : credentialManager.listByUserId(realmId, userId);
        byte[] challenge = challengeService.issueAuthentication(realmKey, userId);
        return optionsFactory.authenticationOptions(challenge, credentials);
    }

    /**
     * 完成登录:校验断言,返回断言所属的账号。
     *
     * @param realmKey 身份域标识
     * @param request  浏览器返回的断言
     * @return 断言结果(用户主键与凭证标识)
     * @throws BusinessException 能力未启用、挑战过期、报文不合法或校验失败
     */
    @Transactional
    public AssertionResult finishAuthentication(String realmKey, WebAuthnAssertionRequest request) {
        requireAvailable();
        WebAuthnChallengeService.AuthenticationChallenge challenge =
                challengeService.consumeAuthentication(realmKey, request.challenge());
        if (challenge == null) {
            throw new BusinessException(ErrorCode.WEBAUTHN_CHALLENGE_INVALID);
        }
        String credentialId = WebAuthnCodec.encode(WebAuthnCodec.decode(request.rawId()));
        WebAuthnCredential credential = credentialManager.findByCredentialId(credentialId);
        if (credential == null) {
            // 不区分"凭证不存在"与"签名错误":两者都只说明这次断言不可用
            throw new BusinessException(ErrorCode.WEBAUTHN_VERIFICATION_FAILED);
        }
        if (challenge.boundUserId() != null
                && !challenge.boundUserId().equals(credential.getUserId())) {
            // 挑战发给 A、断言来自 B 的密钥:要么客户端串了数据,要么有人在拼装请求
            log.warn("Passkey 断言与挑战绑定的账号不一致: realm={}, expected={}, actual={}",
                    realmKey, challenge.boundUserId(), credential.getUserId());
            throw new BusinessException(ErrorCode.WEBAUTHN_VERIFICATION_FAILED);
        }
        if (request.userHandle() != null && !request.userHandle().isBlank()
                && !request.userHandle().equals(credential.getUserHandle())) {
            log.warn("Passkey 断言的用户句柄与凭证不符: realm={}, credentialId={}",
                    realmKey, credentialId);
            throw new BusinessException(ErrorCode.WEBAUTHN_VERIFICATION_FAILED);
        }
        AuthenticationData data = verifyAssertion(challenge.challenge(), credential, request);
        long newCounter = data.getAuthenticatorData().getSignCount();
        long oldCounter = credential.getSignCount() == null ? 0L : credential.getSignCount();
        if (newCounter != 0 && oldCounter != 0 && newCounter <= oldCounter) {
            // 计数器不增反降:同一把密钥的私钥在两处存在,按克隆处理
            log.warn("Passkey 签名计数器回退,疑似密钥被克隆: realm={}, userId={}, old={}, new={}",
                    realmKey, credential.getUserId(), oldCounter, newCounter);
            throw new BusinessException(ErrorCode.WEBAUTHN_VERIFICATION_FAILED);
        }
        credentialManager.touchUsage(credential.getId(), newCounter, data.getAuthenticatorData().isFlagBS());
        log.info("Passkey 登录校验通过: realm={}, userId={}, credentialId={}",
                realmKey, credential.getUserId(), credentialId);
        return new AssertionResult(credential.getUserId(), credentialId);
    }

    // ===== 凭证管理 =====

    /**
     * 列出某用户已绑定的凭证。
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @return 凭证视图列表
     */
    public List<WebAuthnCredentialResponse> list(Long realmId, Long userId) {
        return credentialManager.listByUserId(realmId, userId).stream()
                .map(WebAuthnCredentialService::toResponse)
                .toList();
    }

    /**
     * 统计某用户已绑定的凭证数量。
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @return 数量
     */
    public int count(Long realmId, Long userId) {
        return credentialManager.countByUserId(realmId, userId);
    }

    /**
     * 重命名凭证。
     *
     * @param userId      用户主键
     * @param id          凭证主键
     * @param displayName 新名称
     * @throws BusinessException 凭证不存在或不属于该用户
     */
    @Transactional
    public void rename(Long userId, Long id, String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "名称不能为空");
        }
        if (name.length() > 120) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "名称过长");
        }
        if (!credentialManager.rename(id, userId, name)) {
            throw new BusinessException(ErrorCode.WEBAUTHN_CREDENTIAL_NOT_FOUND);
        }
    }

    /**
     * 删除凭证。
     * <p>允许删到一把不剩:用户可能改用口令 + 动态口令登录,
     * 强制"至少留一把"会让换设备的人卡在删不掉旧密钥的死角里。</p>
     *
     * @param userId 用户主键
     * @param id     凭证主键
     * @throws BusinessException 凭证不存在或不属于该用户
     */
    @Transactional
    public void delete(Long userId, Long id) {
        if (!credentialManager.delete(id, userId)) {
            throw new BusinessException(ErrorCode.WEBAUTHN_CREDENTIAL_NOT_FOUND);
        }
        Long realmId = userProvider.findById(userId).map(UserModel::realmId).orElse(null);
        if (realmId != null) {
            securityAlertService.alertCredentialChanged(realmId, userId, "已删除 Passkey",
                    "你的账号删除了一个 Passkey。此后该设备不能再凭它免口令登录。");
        }
    }

    /**
     * 作废某用户的全部凭证(账号停用、密钥疑似泄露时使用)。
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @return 被作废的数量
     */
    @Transactional
    public int revokeAll(Long realmId, Long userId) {
        int deleted = credentialManager.deleteAll(realmId, userId);
        // 批量作废通常发生在账号停用或密钥疑似泄露时,本人需要知道免口令入口已不可用
        if (deleted > 0) {
            securityAlertService.alertCredentialChanged(realmId, userId, "已清空 Passkey",
                    "你的账号下 " + deleted + " 个 Passkey 已全部作废,如需继续免口令登录请重新添加。");
        }
        return deleted;
    }

    // ===== 内部实现 =====

    /**
     * 断言结果。
     *
     * @param userId       凭证所属用户主键
     * @param credentialId 本次使用的凭证标识
     */
    public record AssertionResult(Long userId, String credentialId) {
    }

    /** 能力未启用时直接拒绝,避免把"没配好"表现成"验证失败"。 */
    private void requireAvailable() {
        if (!properties.isAvailable()) {
            throw new BusinessException(ErrorCode.WEBAUTHN_NOT_CONFIGURED);
        }
    }

    /**
     * 取认证器型号标识(仅用于展示)。
     *
     * @param attested 凭证数据
     * @return AAGUID 字符串;认证器未提供时返回 null
     */
    private static String aaguidOf(AttestedCredentialData attested) {
        if (attested.getAaguid() == null || attested.getAaguid().getValue() == null) {
            return null;
        }
        return attested.getAaguid().getValue().toString();
    }

    /**
     * 解析用户句柄:已有凭证沿用同一个,首次绑定时新生成。
     * <p>同一账号的多把密钥共用一个句柄,无用户名登录时按句柄定位账号;
     * 句柄变更后旧凭证的断言无法再关联到该账号。</p>
     *
     * @param realmId  身份域主键
     * @param userId   用户主键
     * @param existing 该用户已绑定的凭证
     * @return 复用已有凭证的句柄,无可用句柄时返回新生成的 16 字节随机值
     */
    private byte[] resolveUserHandle(Long realmId, Long userId, List<WebAuthnCredential> existing) {
        for (WebAuthnCredential credential : existing) {
            byte[] handle = WebAuthnCodec.decode(credential.getUserHandle());
            if (handle != null) {
                return handle;
            }
        }
        return WebAuthnCodec.newUserHandle();
    }

    /**
     * 校验证明对象。
     *
     * @param challenge 本次挑战
     * @param request   浏览器返回的凭证数据
     * @return 校验通过的注册数据
     * @throws BusinessException 报文不合法或校验失败
     */
    private RegistrationData verifyAttestation(byte[] challenge, WebAuthnRegisterRequest request) {
        RegistrationRequest registrationRequest = new RegistrationRequest(
                WebAuthnCodec.decode(request.attestationObject()),
                WebAuthnCodec.decode(request.clientDataJSON()),
                new HashSet<>(request.transports() == null ? List.of() : request.transports()));
        RegistrationParameters parameters = new RegistrationParameters(
                serverProperty(challenge), PUB_KEY_CRED_PARAMS,
                properties.isUserVerificationRequired(), true);
        try {
            return webAuthnManager.verify(registrationRequest, parameters);
        } catch (DataConversionException | VerificationException e) {
            log.warn("Passkey 注册校验失败: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.WEBAUTHN_VERIFICATION_FAILED);
        }
    }

    /**
     * 校验断言。
     *
     * @param challenge  本次挑战
     * @param credential 已保存的凭证(提供公钥与计数器基准)
     * @param request    浏览器返回的断言
     * @return 校验通过的断言数据
     * @throws BusinessException 报文不合法或校验失败
     */
    private AuthenticationData verifyAssertion(byte[] challenge, WebAuthnCredential credential,
                                               WebAuthnAssertionRequest request) {
        AttestedCredentialData attested = credentialDataConverter.convert(
                WebAuthnCodec.decode(credential.getCredentialData()));
        // 注册期落库的备份与计数器基准随凭证记录还原;证明语句与扩展输出不落库,按空传入
        CredentialRecord credentialRecord = new CredentialRecordImpl(
                null,
                null,
                credential.getBackupEligible(),
                credential.getBackupState(),
                credential.getSignCount() == null ? 0L : credential.getSignCount(),
                attested,
                null,
                null,
                null,
                null);
        AuthenticationRequest authenticationRequest = new AuthenticationRequest(
                WebAuthnCodec.decode(request.rawId()),
                WebAuthnCodec.decode(request.userHandle()),
                WebAuthnCodec.decode(request.authenticatorData()),
                WebAuthnCodec.decode(request.clientDataJSON()),
                WebAuthnCodec.decode(request.signature()));
        AuthenticationParameters parameters = new AuthenticationParameters(
                serverProperty(challenge), credentialRecord, List.of(), 
                properties.isUserVerificationRequired(), true);
        try {
            return webAuthnManager.verify(authenticationRequest, parameters);
        } catch (DataConversionException | VerificationException e) {
            log.warn("Passkey 断言校验失败: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.WEBAUTHN_VERIFICATION_FAILED);
        }
    }

    /**
     * 服务端属性:依赖方标识 + 来源 + 本次挑战。
     *
     * @param challenge 本次挑战
     * @return 服务端属性
     */
    private ServerProperty serverProperty(byte[] challenge) {
        return ServerProperty.builder()
                .origin(new Origin(properties.getOrigin()))
                .rpId(properties.getRpId())
                .challenge(new DefaultChallenge(challenge))
                .build();
    }

    /**
     * 传输方式拼接成逗号分隔串。
     *
     * @param transports 浏览器上报的传输方式
     * @return 逗号分隔串;为空时返回 null(列允许为空)
     */
    private static String joinTransports(List<String> transports) {
        if (transports == null || transports.isEmpty()) {
            return null;
        }
        Set<String> unique = new HashSet<>();
        StringBuilder sb = new StringBuilder();
        for (String transport : transports) {
            if (transport == null || transport.isBlank()) {
                continue;
            }
            String normalized = transport.trim().toLowerCase(Locale.ROOT);
            if (!unique.add(normalized)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(normalized);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 名称兜底:用户没填就按序号生成,列表里总得能区分开。
     *
     * @param displayName 用户填写的名称
     * @param existing    已有凭证数量
     * @return 最终名称
     */
    private static String displayNameOrDefault(String displayName, int existing) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        return DEFAULT_NAME_PREFIX + (existing + 1);
    }

    /**
     * 转对外视图。
     *
     * @param credential 凭证实体
     * @return 视图对象
     */
    private static WebAuthnCredentialResponse toResponse(WebAuthnCredential credential) {
        List<String> transports = new ArrayList<>(WebAuthnOptionsFactory.splitTransports(credential.getTransports()));
        return new WebAuthnCredentialResponse(
                credential.getId(),
                credential.getDisplayName(),
                credential.getAaguid(),
                transports,
                credential.getBackupEligible(),
                credential.getBackupState(),
                credential.getLastUsedTime(),
                credential.getCreatedTime());
    }
}
