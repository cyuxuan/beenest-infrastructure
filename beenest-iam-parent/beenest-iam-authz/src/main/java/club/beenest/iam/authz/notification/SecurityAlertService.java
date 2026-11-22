package club.beenest.iam.authz.notification;

import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 安全提醒投递。
 * <p>口令、动态口令与 Passkey 的变更,以及管理员开通账号,都会向本人邮箱发送一条提醒。
 * 这类邮件不是通知偏好,而是账号被接管时的唯一独立线索:攻击者取得凭据后第一件事通常是
 * 改密或换绑第二因素,受害者只有从"不是我做的"这条提醒里才能发现。</p>
 * <p>投递在事务提交后异步执行:邮件通道慢或不可用不能拖慢甚至失败一次改密,事务回滚时
 * 也不该留下一条"已经提醒过"的记录。队列满时丢弃并留日志,不反压登录与改密链路。</p>
 */
@Service
public class SecurityAlertService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAlertService.class);

    /** 提醒时间格式,精确到分钟即可 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 并发投递线程数:邮件发送是 IO 等待,不需要更多线程 */
    private static final int POOL_SIZE = 2;
    /** 待投递队列长度:突发操作(批量重置口令)时吸收瞬时高峰 */
    private static final int QUEUE_CAPACITY = 512;

    private final NotificationService notificationService;
    private final RealmProvider realmProvider;
    private final UserProvider userProvider;

    private final ExecutorService executor = new ThreadPoolExecutor(
            POOL_SIZE, POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "iam-security-alert");
                // 守护线程:投递不应阻止进程退出
                thread.setDaemon(true);
                return thread;
            },
            (runnable, pool) -> log.warn("安全提醒队列已满,丢弃一条提醒"));

    public SecurityAlertService(NotificationService notificationService,
                                RealmProvider realmProvider,
                                UserProvider userProvider) {
        this.notificationService = notificationService;
        this.realmProvider = realmProvider;
        this.userProvider = userProvider;
    }

    /**
     * 发送一条凭证变更提醒。
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @param title   提醒标题,同时作为邮件主题前缀
     * @param summary 提醒正文,说明刚发生的变化
     */
    public void alertCredentialChanged(Long realmId, Long userId, String title, String summary) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("alertTitle", title);
        variables.put("alertSummary", summary);
        submitAfterCommit(() -> send(realmId, userId,
                NotificationService.TEMPLATE_SECURITY_ALERT, title, variables));
    }

    /**
     * 发送一条"新 IP 登录"提醒。
     * <p>与凭证变更分开命名:前者说明"有人改了安全设置",后者说明"有人在别处用了账号",
     * 两者在处置动作上不同(改密与踢下线),邮件正文也要能直接指导用户。</p>
     *
     * @param realmId    身份域主键
     * @param userId     用户主键
     * @param ip         本次登录来源 IP
     * @param previousIp 上次成功登录 IP
     * @param loginType  登录方式
     */
    public void alertNewLoginIp(Long realmId, Long userId, String ip, String previousIp, String loginType) {
        String method = loginType == null || loginType.isBlank() ? "" : "(" + loginType + ")";
        Map<String, Object> variables = new HashMap<>();
        variables.put("alertTitle", "检测到新 IP 登录");
        variables.put("alertSummary", "账号本次从 " + ip + method + " 登录,上次成功登录来自 "
                + previousIp + "。若这不是你本人操作,请立即修改口令,并在「账号中心 - 登录设备」"
                + "结束不认识会话。");
        submitAfterCommit(() -> send(realmId, userId,
                NotificationService.TEMPLATE_SECURITY_ALERT, "新 IP 登录", variables));
    }

    /**
     * 告知用户账号已开通,并引导其自助设置口令。
     * <p>邮件里不放初始口令:口令一旦写进邮件就长期留在收件箱与转发链里,
     * 与"管理员设定的初始口令必须首次登录改掉"的约束也互相矛盾。</p>
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     */
    public void accountOpened(Long realmId, Long userId) {
        submitAfterCommit(() -> send(realmId, userId,
                NotificationService.TEMPLATE_ACCOUNT_OPENED, "账号已开通", Map.of()));
    }

    /**
     * 投递线程的生命周期收口。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * 把投递排到事务提交之后;没有事务时立即排队。
     *
     * @param task 投递任务
     */
    private void submitAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(task);
                }
            });
            return;
        }
        submit(task);
    }

    /**
     * 提交到投递线程池。
     *
     * @param task 投递任务
     */
    private void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (RuntimeException e) {
            // 拒绝策略之外仍可能因关闭中抛异常,提醒失败不影响主流程
            log.warn("安全提醒排队失败: {}", e.getMessage());
        }
    }

    /**
     * 装配变量并投递。
     * <p>收件人邮箱缺失、通道未配置或域/用户已不存在时静默跳过,失败只留日志。</p>
     *
     * @param realmId      身份域主键
     * @param userId       用户主键
     * @param templateCode 模板编码
     * @param subject      邮件主题前缀
     * @param extra        模板专有变量
     */
    private void send(Long realmId, Long userId, String templateCode, String subject,
                      Map<String, Object> extra) {
        try {
            RealmModel realm = realmProvider.findById(realmId).orElse(null);
            UserModel user = userProvider.findById(userId).orElse(null);
            if (realm == null || user == null) {
                log.debug("安全提醒跳过:域或用户已不存在, realmId={}, userId={}", realmId, userId);
                return;
            }
            if (user.email() == null || user.email().isBlank()) {
                log.debug("安全提醒跳过:用户未填写邮箱, userId={}", userId);
                return;
            }
            Map<String, Object> variables = new HashMap<>(extra);
            variables.put("username", displayName(user));
            variables.put("appName", realm.realmName());
            variables.put("occurredAt", LocalDateTime.now().format(TIME_FORMAT));
            variables.put("year", Year.now().getValue());
            notificationService.sendMail(realm.id(), realm.realmKey(), userId, user.email(),
                    templateCode, subject + " - " + realm.realmName(), variables);
        } catch (RuntimeException e) {
            log.warn("安全提醒投递失败: realmId={}, userId={}, template={}, error={}",
                    realmId, userId, templateCode, e.getMessage());
        }
    }

    /**
     * 收件人称呼:优先昵称,缺失时用登录名。
     *
     * @param user 用户快照
     * @return 展示名
     */
    private static String displayName(UserModel user) {
        return user.nickname() == null || user.nickname().isBlank() ? user.username() : user.nickname();
    }
}
