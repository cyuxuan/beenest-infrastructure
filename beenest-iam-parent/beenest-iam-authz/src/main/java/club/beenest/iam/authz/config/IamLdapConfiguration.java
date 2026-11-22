package club.beenest.iam.authz.config;

import club.beenest.iam.authz.ldap.JndiLdapGateway;
import club.beenest.iam.authz.ldap.LdapGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LDAP/AD 用户联合的 Bean 注册。
 * <p>网关采用 JDK 内置的 JNDI 实现,AD 与 OpenLDAP 通用,无三方依赖;测试可注入内存替身替换。</p>
 */
@Configuration
public class IamLdapConfiguration {

    /**
     * 注册 LDAP 网关。
     *
     * @return JNDI 实现的 LDAP 网关
     */
    @Bean
    public LdapGateway ldapGateway() {
        return new JndiLdapGateway();
    }
}
