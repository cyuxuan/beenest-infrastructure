package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.LdapSyncRequest;
import club.beenest.iam.admin.dto.LdapSyncResponse;
import club.beenest.iam.admin.service.LdapSyncService;
import club.beenest.iam.authz.ldap.LdapGateway;
import club.beenest.iam.authz.ldap.LdapSettings;
import club.beenest.iam.authz.ldap.LdapSettingsFactory;
import club.beenest.iam.authz.ldap.LdapUnavailableException;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.security.IamSecretCrypto;
import club.beenest.iam.persistence.converter.UserConverter;
import club.beenest.iam.persistence.entity.LdapConfig;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.manager.IamLdapConfigStore;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LDAP/AD 目录用户全量同步实现。
 * <p>同步口径:</p>
 * <ul>
 *   <li>只补建、不删除:目录中已移除的账号不会在本地被禁用或删除,避免一次误配置清空用户;</li>
 *   <li>本地账号优先:已持有本地口令的同名账号整体跳过,本地口令、角色、状态一律不覆盖;</li>
 *   <li>只补空缺:已联合账号仅补全缺失的邮箱、昵称与目录来源属性;</li>
 *   <li>邮箱冲突不写入:目录邮箱已被其他本地账号占用时,账号照建但不带邮箱,
 *       避免找回口令按邮箱命中错误账号;</li>
 *   <li>单条失败不中断:逐条处理,失败原因进样例,不影响其余账号。</li>
 * </ul>
 */
@Service
public class LdapSyncServiceImpl implements LdapSyncService {

    private static final Logger log = LoggerFactory.getLogger(LdapSyncServiceImpl.class);

    /** 缺省读取上限:覆盖常见中型目录,又不会让管理端单次请求跑太久 */
    private static final int DEFAULT_MAX_ENTRIES = 2000;
    /** 样例说明最多保留的条数 */
    private static final int MAX_SAMPLES = 20;
    /** 扩展属性中记录目录来源的键,与 JIT 建号保持一致 */
    private static final String ATTR_LDAP_DN = "ldap_dn";
    private static final String ATTR_LDAP_UUID = "ldap_uuid";

    private final IamLdapConfigStore configStore;
    private final IamSecretCrypto secretCrypto;
    private final LdapGateway ldapGateway;
    private final IamUserManager userManager;
    private final UserConverter userConverter;

    public LdapSyncServiceImpl(IamLdapConfigStore configStore, IamSecretCrypto secretCrypto,
                               LdapGateway ldapGateway, IamUserManager userManager,
                               UserConverter userConverter) {
        this.configStore = configStore;
        this.secretCrypto = secretCrypto;
        this.ldapGateway = ldapGateway;
        this.userManager = userManager;
        this.userConverter = userConverter;
    }

    @Override
    public LdapSyncResponse sync(LdapSyncRequest request) {
        var principal = CurrentUserHolder.require();
        Long realmId = principal.getRealmId();
        boolean dryRun = request != null && Boolean.TRUE.equals(request.dryRun());
        int maxEntries = request == null || request.maxEntries() == null
                ? DEFAULT_MAX_ENTRIES : request.maxEntries();

        LdapConfig config = configStore.getConfig(realmId, principal.getRealmKey());
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            return failed(dryRun, "LDAP 用户联合未启用,请先在目录配置中启用后再同步");
        }
        LdapSettings settings = LdapSettingsFactory.from(realmId, config, secretCrypto);
        if (settings == null) {
            return failed(dryRun, "LDAP 配置不完整或目录地址非法,请检查目录地址与用户搜索基");
        }

        long startedAt = System.nanoTime();
        List<LdapGateway.LdapEntry> entries;
        try {
            entries = ldapGateway.searchAll(settings, syncFilter(config.getUserFilter()), maxEntries);
        } catch (LdapUnavailableException e) {
            log.warn("LDAP 全量同步失败: realmId={}, reason={}", realmId, e.getMessage());
            return failed(dryRun, "目录不可达,请先在目录配置中测试连接:" + e.getMessage());
        }

        Counters counters = new Counters();
        List<String> samples = new ArrayList<>();
        for (LdapGateway.LdapEntry entry : entries) {
            counters.scanned++;
            try {
                EntryResult result = syncEntry(realmId, config, entry, dryRun);
                counters.add(result.outcome());
                if (result.note() != null && samples.size() < MAX_SAMPLES) {
                    samples.add(result.note());
                }
            } catch (RuntimeException e) {
                // 单条异常(属性超长、唯一键冲突等)不能拖垮整批同步
                counters.failed++;
                log.warn("LDAP 同步单条失败: realmId={}, dn={}, reason={}",
                        realmId, entry.dn(), e.getMessage());
                if (samples.size() < MAX_SAMPLES) {
                    samples.add(entry.dn() + ": " + e.getMessage());
                }
            }
        }
        boolean truncated = entries.size() >= maxEntries;
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("LDAP 全量同步完成: realmId={}, dryRun={}, scanned={}, created={}, updated={}, "
                        + "unchanged={}, skipped={}, failed={}, truncated={}",
                realmId, dryRun, counters.scanned, counters.created, counters.updated,
                counters.unchanged, counters.skipped, counters.failed, truncated);
        return new LdapSyncResponse(true, dryRun, truncated, counters.scanned, counters.created,
                counters.updated, counters.unchanged, counters.skipped, counters.failed,
                durationMs, samples, summary(counters, dryRun, truncated, maxEntries));
    }

    /**
     * 处理单个目录条目。
     *
     * @param realmId 当前域主键
     * @param config  域 LDAP 配置,提供属性映射
     * @param entry   目录条目
     * @param dryRun  预演模式:只统计不落库
     * @return 本条处理结论
     */
    private EntryResult syncEntry(Long realmId, LdapConfig config,
                                  LdapGateway.LdapEntry entry, boolean dryRun) {
        String username = trimToNull(firstAttr(entry, config.getUsernameAttribute()));
        if (username == null) {
            return new EntryResult(Outcome.SKIPPED,
                    entry.dn() + ": 缺少登录名属性 " + config.getUsernameAttribute());
        }
        String email = trimToNull(firstAttr(entry, config.getEmailAttribute()));
        String nickname = trimToNull(firstAttr(entry, config.getDisplayNameAttribute()));
        String uuid = trimToNull(firstAttr(entry, config.getUuidAttribute()));

        User existing = userManager.getByUsername(realmId, username);
        if (existing == null) {
            return createUser(realmId, entry, username, email, nickname, uuid, dryRun);
        }
        if (userManager.hasLocalPassword(existing.getId())) {
            return new EntryResult(Outcome.SKIPPED, username + ": 本地账号,保持本地口令与资料不变");
        }
        return fillMissing(realmId, entry, existing, email, nickname, uuid, dryRun);
    }

    /**
     * 新建本地联合账号。
     *
     * @param realmId  当前域主键
     * @param entry    目录条目
     * @param username 登录名
     * @param email    目录邮箱
     * @param nickname 目录显示名
     * @param uuid     目录稳定标识
     * @param dryRun   预演模式
     * @return 本条处理结论
     */
    private EntryResult createUser(Long realmId, LdapGateway.LdapEntry entry, String username,
                                   String email, String nickname, String uuid, boolean dryRun) {
        String note = null;
        String emailToWrite = email;
        if (email != null && emailTakenByOther(realmId, email, null)) {
            // 邮箱属于另一个本地账号:照建账号但不写邮箱,避免找回口令串号
            emailToWrite = null;
            note = username + ": 目录邮箱 " + email + " 已被其他本地账号占用,账号已建但未写邮箱";
        }
        if (!dryRun) {
            try {
                userManager.createFederatedUser(realmId, username, emailToWrite,
                        nickname != null ? nickname : username, null, ldapAttributes(entry, uuid));
            } catch (DuplicateKeyException e) {
                // 并发同步或用户刚被 JIT:视为已存在,交给下一轮补全
                return new EntryResult(Outcome.UNCHANGED, username + ": 本地已存在同名账号,未重复创建");
            }
        }
        return new EntryResult(Outcome.CREATED, note);
    }

    /**
     * 补全已联合账号的空缺资料与目录来源属性。
     *
     * @param realmId  当前域主键
     * @param entry    目录条目
     * @param user     本地用户实体
     * @param email    目录邮箱
     * @param nickname 目录显示名
     * @param uuid     目录稳定标识
     * @param dryRun   预演模式
     * @return 本条处理结论
     */
    private EntryResult fillMissing(Long realmId, LdapGateway.LdapEntry entry, User user,
                                    String email, String nickname, String uuid, boolean dryRun) {
        boolean changed = false;
        String note = null;
        if (isBlank(user.getEmail()) && email != null) {
            if (emailTakenByOther(realmId, email, user.getId())) {
                note = user.getUsername() + ": 目录邮箱 " + email + " 已被其他本地账号占用,未写入";
            } else {
                user.setEmail(email);
                changed = true;
            }
        }
        if (isBlank(user.getNickname()) && nickname != null) {
            user.setNickname(nickname);
            changed = true;
        }
        Map<String, String> attributes = new LinkedHashMap<>(
                userConverter.parseAttributes(user.getAttributes()));
        if (!entry.dn().equals(attributes.get(ATTR_LDAP_DN))) {
            attributes.put(ATTR_LDAP_DN, entry.dn());
            changed = true;
        }
        if (uuid != null && !uuid.equals(attributes.get(ATTR_LDAP_UUID))) {
            attributes.put(ATTR_LDAP_UUID, uuid);
            changed = true;
        }
        if (!changed) {
            return new EntryResult(Outcome.UNCHANGED, note);
        }
        if (!dryRun) {
            user.setAttributes(userConverter.toAttributeJson(attributes));
            userManager.update(user);
        }
        return new EntryResult(Outcome.UPDATED, note);
    }

    /**
     * 判断邮箱是否已被其他本地账号占用。
     *
     * @param realmId 当前域主键
     * @param email   待写入邮箱
     * @param selfId  当前账号主键(新建时为 null)
     * @return true 表示被其他账号占用
     */
    private boolean emailTakenByOther(Long realmId, String email, Long selfId) {
        return userManager.findByEmail(realmId, email)
                .filter(other -> selfId == null || !selfId.equals(other.id()))
                .isPresent();
    }

    /**
     * 组装 JIT 同口径的目录来源属性。
     *
     * @param entry 目录条目
     * @param uuid  目录稳定标识
     * @return 扩展属性
     */
    private static Map<String, String> ldapAttributes(LdapGateway.LdapEntry entry, String uuid) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_LDAP_DN, entry.dn());
        if (uuid != null) {
            attributes.put(ATTR_LDAP_UUID, uuid);
        }
        return attributes;
    }

    /**
     * 把用户过滤器转成全量过滤器:{0} 以 * 填充,即"所有具备登录名属性的用户"。
     *
     * @param template 用户过滤器模板,如 {@code (sAMAccountName={0})}
     * @return 可直接交给目录的过滤器
     */
    private static String syncFilter(String template) {
        String base = template == null || template.isBlank() ? "(sAMAccountName={0})" : template;
        return base.replace("{0}", "*");
    }

    /**
     * 取条目属性首值。
     *
     * @param entry     目录条目
     * @param attribute 属性名
     * @return 首值;属性名为空或条目无该属性时返回 null
     */
    private static String firstAttr(LdapGateway.LdapEntry entry, String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return null;
        }
        return entry.first(attribute);
    }

    /**
     * 去除首尾空白,空白值返回 null。
     *
     * @param value 原始值
     * @return 去空白后的值
     */
    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 字符串是否为 null 或空白。
     *
     * @param value 待判定字符串
     * @return true 表示为 null 或全空白
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 组装失败报告(平台故障,未读取目录)。
     *
     * @param dryRun 是否预演
     * @param message 失败原因
     * @return success=false 的报告
     */
    private static LdapSyncResponse failed(boolean dryRun, String message) {
        return new LdapSyncResponse(false, dryRun, false, 0, 0, 0, 0, 0, 0, 0, List.of(), message);
    }

    /**
     * 组装结果摘要。
     *
     * @param counters   统计
     * @param dryRun     是否预演
     * @param truncated  是否截断
     * @param maxEntries 读取上限
     * @return 面向运维的摘要
     */
    private static String summary(Counters counters, boolean dryRun, boolean truncated, int maxEntries) {
        String prefix = dryRun ? "预演完成(未写入任何数据):将新建 " : "同步完成:新建 ";
        String text = prefix + counters.created + " 个、"
                + (dryRun ? "将更新 " : "更新 ") + counters.updated + " 个,"
                + "无变化 " + counters.unchanged + " 个,跳过 " + counters.skipped + " 个,"
                + "失败 " + counters.failed + " 个";
        if (truncated) {
            text += ";已达到本次读取上限 " + maxEntries + " 条,剩余账号请调大上限后再次同步";
        }
        return text;
    }

    /** 单条处理结论。 */
    private enum Outcome {
        /** 新建本地账号 */
        CREATED,
        /** 补全已有账号 */
        UPDATED,
        /** 无需变更 */
        UNCHANGED,
        /** 按规则跳过 */
        SKIPPED
    }

    /**
     * 单条处理结论与说明。
     *
     * @param outcome 结论
     * @param note    追加到样例的说明,可为 null
     */
    private record EntryResult(Outcome outcome, String note) {
    }

    /** 同步过程计数。 */
    private static final class Counters {
        private int scanned;
        private int created;
        private int updated;
        private int unchanged;
        private int skipped;
        private int failed;

        /**
         * 按结论累加计数。
         *
         * @param outcome 单条结论
         */
        private void add(Outcome outcome) {
            switch (outcome) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
                case SKIPPED -> skipped++;
            }
        }
    }
}
