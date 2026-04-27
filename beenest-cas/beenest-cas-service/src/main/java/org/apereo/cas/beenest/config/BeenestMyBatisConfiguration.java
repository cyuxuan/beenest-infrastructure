package org.apereo.cas.beenest.config;

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Beenest 业务库的 MyBatis 配置。
 * <p>
 * 独立出来避免认证自动配置和 MyBatis 初始化互相形成循环依赖。
 */
@AutoConfiguration
@MapperScan(basePackages = "org.apereo.cas.beenest.mapper", sqlSessionTemplateRef = "sqlSessionTemplate")
public class BeenestMyBatisConfiguration {

    @Bean
    public SqlSessionFactory sqlSessionFactory(@Qualifier("dataSourceService") final DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml"));

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(final SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    /**
     * 为 Beenest 业务库提供独立的事务管理器。
     * <p>
     * CAS 容器中同时存在多个 TransactionManager，用户身份相关的事务必须明确绑定到
     * 业务库自己的事务管理器，避免 Spring 在运行时误选到 CAS 内部的事务管理器。
     *
     * @param dataSource Beenest 业务库数据源
     * @return 业务库事务管理器
     */
    @Bean(name = "beenestTransactionManager")
    public PlatformTransactionManager beenestTransactionManager(@Qualifier("dataSourceService") final DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
