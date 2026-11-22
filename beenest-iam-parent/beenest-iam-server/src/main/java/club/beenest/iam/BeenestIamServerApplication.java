package club.beenest.iam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 蜂巢身份 (beenest IAM) 服务启动入口。
 * <p>唯一可执行进程,聚合 common/core/persistence/federation/authz/admin/console 全部库模块,
 * 承载 OIDC 协议端点(/realms/{realmKey}/**)与业务接口(/api/v1/**)。</p>
 * <p>开启定时任务:审计日志留存期清理依赖它。</p>
 */
@SpringBootApplication
@EnableScheduling
public class BeenestIamServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BeenestIamServerApplication.class, args);
    }

}
