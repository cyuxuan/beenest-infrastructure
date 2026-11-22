package club.beenest.iam.authz.bootstrap;

import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.manager.IamRoleManager;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 引导管理员初始化。
 * <p>初始口令只能来自环境变量,绝不写入初始化脚本,避免仓库里留下可用凭据。
 * 口令为空时静默跳过并告警;账号已存在时不覆盖密码,保证重启幂等。</p>
 */
@Component
@ConditionalOnProperty(prefix = "iam.bootstrap", name = "enabled", havingValue = "true")
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    /** 超级管理员角色编码(由初始化脚本预置) */
    private static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";
    /** 引导账号名称 */
    private static final String ADMIN_NICKNAME = "系统管理员";

    private final RealmProvider realmProvider;
    private final IamUserManager userManager;
    private final IamRoleManager roleManager;

    @Value("${iam.bootstrap.realm-key:default}")
    private String realmKey;
    @Value("${iam.bootstrap.admin-username:admin}")
    private String adminUsername;
    @Value("${iam.bootstrap.admin-password:}")
    private String adminPassword;

    public BootstrapAdminRunner(RealmProvider realmProvider, IamUserManager userManager,
                                IamRoleManager roleManager) {
        this.realmProvider = realmProvider;
        this.userManager = userManager;
        this.roleManager = roleManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 1. 未提供口令则不创建账号:不以任何默认口令启动系统
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("未配置 iam.bootstrap.admin-password,跳过引导管理员创建");
            return;
        }
        // 2. 目标域必须存在
        RealmModel realm = realmProvider.findByRealmKey(realmKey).orElse(null);
        if (realm == null) {
            log.warn("引导管理员所属身份域不存在,跳过创建: realmKey={}", realmKey);
            return;
        }
        // 3. 已存在则跳过,保证重启幂等且不覆盖已修改的密码
        if (userManager.getByUsername(realm.id(), adminUsername) != null) {
            log.info("引导管理员已存在,跳过创建: realmKey={}, username={}", realmKey, adminUsername);
            return;
        }
        // 4. 创建管理员账号并写入初始口令
        User admin = new User();
        admin.setRealmId(realm.id());
        admin.setUsername(adminUsername);
        admin.setNickname(ADMIN_NICKNAME);
        admin.setStatus(1);
        admin.setUserType("INTERNAL");
        admin.setEmailVerified(false);
        admin.setTokenVersion(0);
        admin.setAttributes("{}");
        userManager.create(admin, adminPassword);
        // 5. 授予超级管理员角色,使引导账号立即可用
        assignSuperAdmin(realm.id(), admin.getId());
        log.info("引导管理员创建完成: realmKey={}, username={}", realmKey, adminUsername);
    }

    /**
     * 授予 SUPER_ADMIN 角色。
     * <p>角色缺失时告警并保持引导账号无权限,不阻断启动。</p>
     *
     * @param realmId 身份域主键
     * @param userId  引导管理员用户主键
     */
    private void assignSuperAdmin(Long realmId, Long userId) {
        Role role = roleManager.getByScope(realmId, "REALM", null, null, SUPER_ADMIN_ROLE);
        if (role == null) {
            log.warn("未找到 {} 角色,引导管理员暂无任何权限", SUPER_ADMIN_ROLE);
            return;
        }
        roleManager.assignRoles(realmId, userId, List.of(role.getId()), userId);
    }
}
