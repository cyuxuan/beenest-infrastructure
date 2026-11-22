package club.beenest.iam.authz.logout;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.subject.IamSubjectResolver;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.model.ClientSessionModel;
import club.beenest.iam.core.model.LogoutBroadcastModel;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.core.spi.provider.ClientSessionStore;
import club.beenest.iam.core.spi.provider.LogoutBroadcastStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 单点登出广播服务,向用户登录过的应用逐个推送 OIDC 后通道登出通知。
 * <p>并发投递并在总预算内收口,超时任务取消并记为失败;任一应用投递异常不阻断登出,
 * 失败留痕供运营追查与补发。未登记接收地址的应用当场记为 SKIPPED。</p>
 * <p>sub 按每个应用各自的解析策略逐应用计算,与该应用所获 ID Token 中的 sub 同值。</p>
 */
@Service
public class IamLogoutBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(IamLogoutBroadcastService.class);

    /** 单个应用的最长等待时间:域名解析、TLS 握手、响应都算在内 */
    private static final Duration PER_TARGET_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 一律拒绝的接收地址:云厂商元数据服务(链路本地段 169.254.0.0/16 与常见元数据主机名)。
     * <p>后通道登出的目标地址由管理员配置,配置错误或被篡改时本服务会成为从外网探测
     * 内网的信道,这里做一层兜底拦截。仅为纵深防御,完整 SSRF 防护需解析后校验真实 IP。</p>
     */
    private static final Set<String> BLOCKED_HOSTS =
            Set.of("metadata", "metadata.google.internal", "instance-data");
    private static final String LINK_LOCAL_PREFIX = "169.254.";

    private final ClientSessionStore clientSessionStore;
    private final ClientProvider clientProvider;
    private final LogoutBroadcastStore broadcastStore;
    private final LogoutTokenIssuer tokenIssuer;
    private final IamSubjectResolver subjectResolver;
    private final String configuredBaseUrl;
    private final long budgetMillis;
    /** 投递线程池:登出是低频动作,固定小池足够,且避免每次登出新建线程 */
    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "iam-logout-broadcast");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public IamLogoutBroadcastService(ClientSessionStore clientSessionStore,
                                     ClientProvider clientProvider,
                                     LogoutBroadcastStore broadcastStore,
                                     LogoutTokenIssuer tokenIssuer,
                                     IamSubjectResolver subjectResolver,
                                     @Value("${iam.issuer-base-url:}") String configuredBaseUrl,
                                     @Value("${iam.logout.broadcast-timeout-ms:2000}") long budgetMillis) {
        this.clientSessionStore = clientSessionStore;
        this.clientProvider = clientProvider;
        this.broadcastStore = broadcastStore;
        this.tokenIssuer = tokenIssuer;
        this.subjectResolver = subjectResolver;
        this.configuredBaseUrl = configuredBaseUrl;
        this.budgetMillis = budgetMillis <= 0 ? 2000L : budgetMillis;
    }

    /**
     * 广播某个会话的登出。
     *
     * @param realmId  身份域主键
     * @param realmKey 身份域标识(签名与 issuer 使用)
     * @param sid       被结束的会话标识
     * @param principal 被登出的用户主体(为空则只带 sid);sub 按<b>每个应用各自的策略</b>解析
     * @return 本次产生的投递记录数(含 SKIPPED)
     */
    public int broadcast(Long realmId, String realmKey, String sid, IamUserPrincipal principal) {
        return broadcast(realmId, realmKey, sid, principal, null);
    }

    /**
     * 只向指定客户端广播某个会话的登出。
     * <p>授权收回只影响单个应用,不能连带其它应用:按 sid 全量广播会把用户从所有应用
     * 一并踢下线。本方法只投递目标客户端,也只关闭该客户端上的会话记录。</p>
     *
     * @param realmId  身份域主键
     * @param realmKey 身份域标识
     * @param sid      被结束的会话标识
     * @param principal 被登出的用户主体;为空则只带 sid
     * @param clientId 目标客户端主键
     * @return 本次产生的投递记录数(含 SKIPPED)
     */
    public int broadcastToClient(Long realmId, String realmKey, String sid,
                                 IamUserPrincipal principal, Long clientId) {
        if (clientId == null) {
            return 0;
        }
        return broadcast(realmId, realmKey, sid, principal, clientId);
    }

    /**
     * 广播实现:onlyClientId 为空时覆盖会话上的全部应用,否则只处理目标客户端。
     *
     * @param realmId      身份域主键
     * @param realmKey     身份域标识
     * @param sid          被结束的会话标识
     * @param principal    被登出的用户主体
     * @param onlyClientId 限定投递的客户端主键;为空表示全部
     * @return 本次产生的投递记录数(含 SKIPPED)
     */
    private int broadcast(Long realmId, String realmKey, String sid, IamUserPrincipal principal,
                          Long onlyClientId) {
        if (realmId == null || sid == null || sid.isBlank()) {
            return 0;
        }
        // 1. 会话登录过的客户端:没有记录说明用户从未在本平台授权过应用,无需广播
        List<ClientSessionModel> sessions = clientSessionStore.listBySid(realmId, sid);
        if (onlyClientId != null) {
            sessions = sessions.stream()
                    .filter(session -> onlyClientId.equals(session.clientId()))
                    .toList();
        }
        if (sessions.isEmpty()) {
            return 0;
        }
        String baseUrl = resolveBaseUrl();
        if (baseUrl == null) {
            log.warn("无法确定对外基址,跳过登出广播: realm={}, sid={}", realmKey, sid);
            return 0;
        }
        // 2. 逐应用准备投递任务;未登记接收地址的当场记为 SKIPPED
        List<Callable<LogoutBroadcastModel>> tasks = new ArrayList<>();
        List<LogoutBroadcastModel> records = new ArrayList<>();
        for (ClientSessionModel session : sessions) {
            ClientModel client = clientProvider.findById(session.clientId()).orElse(null);
            if (client == null) {
                continue;
            }
            if (!client.supportsBackchannelLogout()) {
                LogoutBroadcastModel skipped = broadcastStore.insert(LogoutBroadcastModel.pending(
                        realmId, sid, session.userId(), client.id(), client.clientKey(), null));
                broadcastStore.updateResult(skipped.id(), LogoutBroadcastModel.STATE_SKIPPED, null,
                        "应用未登记后通道登出地址");
                continue;
            }
            String targetUri = client.backchannelLogoutUri();
            // sub 必须与该应用拿到的 ID Token 中的 sub 同值,应用据此定位要下线的本地会话;
            // 客户端可单独覆盖策略,故逐个应用解析,不能在整个会话层面算一次
            String subject = subjectResolver.resolve(principal, client);
            LogoutBroadcastModel record = broadcastStore.insert(LogoutBroadcastModel.pending(
                    realmId, sid, session.userId(), client.id(), client.clientKey(), targetUri));
            tasks.add(() -> deliver(record, baseUrl, realmKey, client.clientKey(), subject, sid));
            records.add(record);
        }
        if (tasks.isEmpty()) {
            return sessions.size();
        }
        // 3. 并发投递并在总预算内收口;超时的任务取消并记为失败
        try {
            List<Future<LogoutBroadcastModel>> futures =
                    executor.invokeAll(tasks, budgetMillis, TimeUnit.MILLISECONDS);
            for (int i = 0; i < futures.size(); i++) {
                if (futures.get(i).isCancelled()) {
                    // 超时被取消的任务不会有回写机会,这里补一条失败记录,
                    // 避免该记录停在 PENDING、排障时看不出其实没发出去
                    broadcastStore.updateResult(records.get(i).id(), LogoutBroadcastModel.STATE_FAILED,
                            null, "投递超时(总预算 " + budgetMillis + "ms)");
                    log.warn("登出广播超时未完成: realm={}, sid={}, target={}",
                            realmKey, sid, records.get(i).targetUri());
                }
            }
        } catch (InterruptedException e) {
            // 恢复中断:登出流程本身不能因此失败
            Thread.currentThread().interrupt();
            log.warn("登出广播被中断: realm={}, sid={}", realmKey, sid);
        } finally {
            // 4. 无论投递结果如何,本次涉及的客户端会话都已失效,统一关闭;
            //    定向广播只关闭目标客户端,其它应用不受影响
            if (onlyClientId == null) {
                clientSessionStore.closeBySid(sid);
            } else {
                clientSessionStore.closeBySidAndClient(sid, onlyClientId);
            }
        }
        return sessions.size();
    }

    /**
     * 向单个应用投递登出通知并回写结果。
     * <p>目标地址命中拦截名单时直接记为失败并返回;网络异常、非法地址、签名失败
     * 均在此收口留痕,不向调用方抛出。</p>
     *
     * @param record   该次投递的记录,含目标地址
     * @param baseUrl  对外基址,用于签发登出令牌的 issuer
     * @param realmKey 身份域标识
     * @param clientKey 目标客户端标识,用于签发专属登出令牌与日志
     * @param subject  与该应用 ID Token 同值的 sub
     * @param sid      被结束的会话标识
     * @return 回写结果后的投递记录
     */
    private LogoutBroadcastModel deliver(LogoutBroadcastModel record, String baseUrl, String realmKey,
                                         String clientKey, String subject, String sid) {
        if (isBlockedTarget(record.targetUri())) {
            broadcastStore.updateResult(record.id(), LogoutBroadcastModel.STATE_FAILED, null,
                    "目标地址被安全策略拒绝");
            log.warn("登出广播目标地址被拒绝: client={}, target={}", clientKey, record.targetUri());
            return record;
        }
        try {
            // 1. 签发该应用专属的登出令牌(aud 绑定应用,不能复用)
            String logoutToken = tokenIssuer.issue(baseUrl, realmKey, clientKey, subject, sid);
            // 2. 规范要求以表单方式提交 logout_token
            String body = "logout_token=" + URLEncoder.encode(logoutToken, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(record.targetUri()))
                    .timeout(PER_TARGET_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            // 3. 2xx 视为送达;其余记为失败并保留状态码
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            broadcastStore.updateResult(record.id(),
                    ok ? LogoutBroadcastModel.STATE_SENT : LogoutBroadcastModel.STATE_FAILED,
                    response.statusCode(), ok ? null : "应用返回非 2xx");
            if (!ok) {
                log.warn("登出广播被应用拒绝: client={}, status={}", clientKey, response.statusCode());
            }
            return record;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            broadcastStore.updateResult(record.id(), LogoutBroadcastModel.STATE_FAILED, null, "投递被中断");
            return record;
        } catch (Exception e) {
            // 网络异常、非法地址、签名失败都在此收口:留痕但不抛出
            broadcastStore.updateResult(record.id(), LogoutBroadcastModel.STATE_FAILED, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("登出广播失败: client={}, target={}", clientKey, record.targetUri(), e);
            return record;
        }
    }

    /**
     * 判断目标地址是否属于被拒绝的内网探测目标。
     * <p>只按主机名与链路本地段字面判断,不做 DNS 解析:解析引入的额外往返会吃掉
     * 整个投递预算;解析不出主机名的地址一律拒绝。</p>
     *
     * @param targetUri 后通道登出的目标地址
     * @return true 表示应拒绝投递
     */
    private static boolean isBlockedTarget(String targetUri) {
        if (targetUri == null || targetUri.isBlank()) {
            return true;
        }
        try {
            String host = URI.create(targetUri).getHost();
            if (host == null) {
                return true;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return BLOCKED_HOSTS.contains(lower) || lower.startsWith(LINK_LOCAL_PREFIX);
        } catch (Exception e) {
            // 解析不出主机名的地址一律拒绝,不向未知目标投递
            return true;
        }
    }

    /** 释放投递线程池,容器关闭或上下文重启时回收,避免线程随每次重建累积。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 解析对外基址。
     * <p>配置优先:反向代理场景请求头可能是内网地址。无请求上下文(如后台清理线程)
     * 且未配置时返回 null。</p>
     *
     * @return 去掉末尾斜杠的对外基址;无法确定时返回 null
     */
    private String resolveBaseUrl() {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            String url = configuredBaseUrl.trim();
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return ServletUriComponentsBuilder.fromRequest(servletAttributes.getRequest())
                    .replacePath(null)
                    .replaceQuery(null)
                    .build()
                    .toUriString();
        }
        return null;
    }
}
