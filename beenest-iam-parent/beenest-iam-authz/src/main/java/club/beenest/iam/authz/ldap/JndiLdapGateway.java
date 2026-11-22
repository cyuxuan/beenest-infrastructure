package club.beenest.iam.authz.ldap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.SizeLimitExceededException;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.Control;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.PagedResultsControl;
import javax.naming.ldap.PagedResultsResponseControl;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 基于 JNDI 的 LDAP 网关实现,零三方依赖。
 * <p>目录操作只有搜索与绑定两个动作,JNDI 由 JDK 内置、AD 与 OpenLDAP 原生支持。
 * 超时显式设置,目录不可达时认证链路快速失败。</p>
 * <p>口令校验只走用户 DN 加口令的简单绑定,不做属性比较;bind 口令只进 JNDI 环境,
 * 不进日志与异常消息。</p>
 */
public class JndiLdapGateway implements LdapGateway {

    private static final Logger log = LoggerFactory.getLogger(JndiLdapGateway.class);

    /** JNDI 连接超时属性名(毫秒) */
    private static final String CONNECT_TIMEOUT = "com.sun.jndi.ldap.connect.timeout";
    /** JNDI 读取超时属性名(毫秒) */
    private static final String READ_TIMEOUT = "com.sun.jndi.ldap.read.timeout";
    /** 全量检索单页条数:低于 AD 默认 MaxPageSize(1000),避免单页即触发目录分页上限 */
    private static final int PAGE_SIZE = 500;

    /**
     * 以搜索账号连接目录,按过滤器检索一个条目。
     * <p>单次结果限制为 1,搜索时长受 {@link LdapSettings#readTimeoutMs()} 约束;
     * 搜索账号自身口令错误归为目录不可用而非用户不存在。</p>
     *
     * @param config 域 LDAP 配置
     * @param filter 已填充占位的过滤器
     * @return 命中条目;未命中返回 empty
     * @throws LdapUnavailableException 目录不可达或搜索账号认证失败
     */
    @Override
    public Optional<LdapEntry> search(LdapSettings config, String filter) {
        LdapContext context = null;
        try {
            context = openContext(config, config.bindDn(), config.bindPassword());
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setCountLimit(1);
            controls.setTimeLimit(Math.max(1, config.readTimeoutMs() / 1000));
            NamingEnumeration<SearchResult> results =
                    context.search(config.userBaseDn(), filter, controls);
            if (!results.hasMore()) {
                return Optional.empty();
            }
            return Optional.of(toEntry(results.next()));
        } catch (AuthenticationException e) {
            // 搜索账号口令错误:配置类故障,归为目录不可用而非用户不存在
            throw new LdapUnavailableException("LDAP 搜索账号认证失败,请检查 bind_dn/bind_password 配置", e);
        } catch (NamingException e) {
            throw new LdapUnavailableException("LDAP 搜索失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(context);
        }
    }

    /**
     * 以搜索账号分页检索搜索基下的全部条目,供管理端全量同步使用。
     * <p>RFC 2696 分页结果控件按 {@value #PAGE_SIZE} 条一页循环取数,突破 AD 默认
     * 单次 1000 条的上限;控件以非关键方式下发,目录不支持分页时退化为普通检索。
     * 取满 {@code maxEntries} 即停止,由调用方按"是否恰好取满"判断结果是否被截断。</p>
     *
     * @param config     域 LDAP 配置
     * @param filter     已填充占位的过滤器
     * @param maxEntries 本次最多返回的条目数
     * @return 目录条目列表;无命中或达到上限时返回已取得的条目
     * @throws LdapUnavailableException 目录不可达或搜索账号认证失败
     */
    @Override
    public List<LdapEntry> searchAll(LdapSettings config, String filter, int maxEntries) {
        int limit = Math.max(1, maxEntries);
        LdapContext context = null;
        try {
            context = openContext(config, config.bindDn(), config.bindPassword());
            List<LdapEntry> entries = new ArrayList<>();
            byte[] cookie = null;
            do {
                context.setRequestControls(new Control[]{
                        new PagedResultsControl(Math.min(PAGE_SIZE, limit), cookie, Control.NONCRITICAL)});
                entries.addAll(readPage(context, config, filter, limit - entries.size()));
                cookie = cookieOf(context.getResponseControls());
            } while (cookie != null && cookie.length > 0 && entries.size() < limit);
            return entries;
        } catch (AuthenticationException e) {
            throw new LdapUnavailableException("LDAP 搜索账号认证失败,请检查 bind_dn/bind_password 配置", e);
        } catch (IOException e) {
            throw new LdapUnavailableException("LDAP 分页检索初始化失败: " + e.getMessage(), e);
        } catch (NamingException e) {
            throw new LdapUnavailableException("LDAP 全量检索失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(context);
        }
    }

    /**
     * 读取一页检索结果。
     * <p>目录不支持分页且已到单次上限时,把 {@link SizeLimitExceededException} 视为
     * 本页正常结束:同步按已取得的部分继续,不因目录侧限制整体失败。</p>
     *
     * @param context 已设置分页控件的上下文
     * @param config  域 LDAP 配置,取搜索基与超时
     * @param filter  已填充占位的过滤器
     * @param remaining 本页最多读取的条数
     * @return 本页条目
     * @throws NamingException 检索失败
     */
    private List<LdapEntry> readPage(LdapContext context, LdapSettings config, String filter,
                                     int remaining) throws NamingException {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setCountLimit(0);
        controls.setTimeLimit(Math.max(1, config.readTimeoutMs() / 1000));
        NamingEnumeration<SearchResult> results = context.search(config.userBaseDn(), filter, controls);
        List<LdapEntry> entries = new ArrayList<>();
        try {
            while (entries.size() < remaining && results.hasMore()) {
                entries.add(toEntry(results.next()));
            }
        } catch (SizeLimitExceededException e) {
            log.warn("LDAP 目录单次检索达到上限,后续条目将由下一页继续: {}", e.getMessage());
        } finally {
            results.close();
        }
        return entries;
    }

    /**
     * 从响应控件中取分页 cookie,空表示已到最后一页。
     *
     * @param controls 响应控件,可为 null
     * @return 下一页 cookie;无分页控件或末页返回 null
     */
    private static byte[] cookieOf(Control[] controls) {
        if (controls == null) {
            return null;
        }
        for (Control control : controls) {
            if (control instanceof PagedResultsResponseControl paged) {
                return paged.getCookie();
            }
        }
        return null;
    }

    /**
     * 以用户 DN 与用户口令绑定完成口令校验。
     * <p>空口令直接失败,不往返目录;空口令 bind 在多数目录上报错或匿名通过,均不符预期。</p>
     *
     * @param config   域 LDAP 配置
     * @param userDn   搜索命中的用户 DN
     * @param password 用户输入的原始口令
     * @return 绑定成功 true;口令错误或账号被禁用 false
     * @throws LdapUnavailableException 目录不可达
     */
    @Override
    public boolean bind(LdapSettings config, String userDn, String password) {
        if (password == null || password.isEmpty()) {
            return false;
        }
        LdapContext context = null;
        try {
            context = openContext(config, userDn, password);
            return true;
        } catch (AuthenticationException e) {
            // 口令错误或账号在目录侧被禁用:对上层统一为认证失败
            return false;
        } catch (NamingException e) {
            throw new LdapUnavailableException("LDAP 绑定失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(context);
        }
    }

    /**
     * 打开 LDAP 上下文。
     * <p>超时下限 100ms;referrals 跟随以支持 AD 跨域;dn 为空表示匿名。</p>
     *
     * @param config   域 LDAP 配置,取地址与超时
     * @param dn       绑定 DN,为空表示匿名
     * @param password 绑定口令,为空按空串处理
     * @return 已建立的 LDAP 上下文
     * @throws NamingException JNDI 初始化或连接失败
     */
    private LdapContext openContext(LdapSettings config, String dn, String password) throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, config.serverUrl());
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(CONNECT_TIMEOUT, String.valueOf(Math.max(100, config.connectTimeoutMs())));
        env.put(READ_TIMEOUT, String.valueOf(Math.max(100, config.readTimeoutMs())));
        // referrals 跟随:AD 跨域引用场景下搜索基内可能出现引用
        env.put(Context.REFERRAL, "follow");
        if (dn != null && !dn.isEmpty()) {
            env.put(Context.SECURITY_PRINCIPAL, dn);
            env.put(Context.SECURITY_CREDENTIALS, password == null ? "" : password);
        }
        return new InitialLdapContext(env, null);
    }

    /**
     * 将目录检索结果转为网关条目,属性名大小写不敏感。
     * <p>二进制属性(如 AD objectGUID)以十六进制串保留原始形态。</p>
     *
     * @param result JNDI 检索结果
     * @return 网关条目,DN 取 nameInNamespace
     * @throws NamingException 读取属性失败
     */
    private LdapEntry toEntry(SearchResult result) throws NamingException {
        Map<String, List<String>> attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Attributes raw = result.getAttributes();
        NamingEnumeration<String> ids = raw.getIDs();
        while (ids.hasMore()) {
            String id = ids.next();
            List<String> values = new ArrayList<>();
            NamingEnumeration<?> valueEnum = raw.get(id).getAll();
            while (valueEnum.hasMore()) {
                Object value = valueEnum.next();
                values.add(value instanceof byte[] bytes ? toHex(bytes) : String.valueOf(value));
            }
            attributes.put(id, values);
        }
        return new LdapEntry(result.getNameInNamespace(), attributes);
    }

    /**
     * 将二进制属性转为十六进制字符串,用于 AD objectGUID 等固定长度值的稳定可读形态。
     *
     * @param bytes 原始字节数组
     * @return 小写十六进制字符串
     */
    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    /**
     * 静默关闭上下文,不影响认证结论。
     *
     * @param context 待关闭的上下文,可为 null
     */
    private void closeQuietly(LdapContext context) {
        if (context != null) {
            try {
                context.close();
            } catch (NamingException e) {
                log.debug("关闭 LDAP 上下文失败(不影响认证结论): {}", e.getMessage());
            }
        }
    }
}
