package club.beenest.iam.authz.ldap;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LDAP 目录网关,登录链路只需的两个动作。
 * <p>接口供认证编排离线测试:JNDI 实现依赖真实目录,测试替身在内存中回答绑定与检索。
 * 实现按 {@link LdapSettings} 中的超时连接目录;目录不可达一律抛
 * {@link LdapUnavailableException}。</p>
 */
public interface LdapGateway {

    /**
     * 以配置的搜索账号(或匿名)连接目录并按过滤器检索一个条目。
     *
     * @param config  域 LDAP 配置,含地址、绑定账号、搜索基与超时
     * @param filter  已填充占位的过滤器
     * @return 命中条目;未命中返回 empty
     * @throws LdapUnavailableException 目录不可达
     */
    Optional<LdapEntry> search(LdapSettings config, String filter);

    /**
     * 以搜索账号检索搜索基下的全部条目,用于管理端全量同步。
     * <p>实现按页向目录取数,避免 AD 默认单次最多返回 1000 条的限制;
     * 结果条数不超过 {@code maxEntries},达到上限即截断返回。</p>
     *
     * @param config     域 LDAP 配置
     * @param filter     已填充占位的过滤器({@code {0}} 以 {@code *} 填充表示全部用户)
     * @param maxEntries 本次最多返回的条目数,必须为正数
     * @return 目录条目列表,按目录返回顺序;无命中返回空列表
     * @throws LdapUnavailableException 目录不可达
     */
    List<LdapEntry> searchAll(LdapSettings config, String filter, int maxEntries);

    /**
     * 以用户 DN 与用户输入口令重新绑定,完成口令校验。
     * <p>AD/LDAP 口令验证的唯一可靠方式:先搜索定位 DN,再以该 DN bind。简单绑定
     * 之外的比较属性做法都无法覆盖目录自身的口令策略(过期、需改密、锁定)。</p>
     *
     * @param config   域 LDAP 配置
     * @param userDn   搜索命中的用户 DN
     * @param password 用户输入的原始口令
     * @return 绑定成功 true;口令错误 false
     * @throws LdapUnavailableException 目录不可达
     */
    boolean bind(LdapSettings config, String userDn, String password);

    /**
     * 检索到的目录条目:DN 与原始属性,多值属性保留全部值。
     *
     * @param dn         条目 DN
     * @param attributes 属性名到值列表的映射
     */
    record LdapEntry(String dn, Map<String, List<String>> attributes) {

        /**
         * 取属性首个值。
         *
         * @param attribute 属性名
         * @return 首值;属性不存在或值为空时返回 null
         */
        public String first(String attribute) {
            List<String> values = attributes.get(attribute);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }
}
