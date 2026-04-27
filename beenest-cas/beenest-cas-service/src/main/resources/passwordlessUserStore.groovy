import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * CAS Passwordless 用户存储（Groovy 脚本）。
 * <p>
 * 从 cas_user 表查询用户信息，支持用户名、手机号、邮箱三种方式查找。
 * CAS Passwordless 模块会调用此脚本获取用户的 phone/email 用于发送 Magic Link。
 */
def run(Object[] args) {
    def (username, logger) = args
    def dataSource = applicationContext.getBean("dataSource", DataSource.class)
    def jdbc = new JdbcTemplate(dataSource)

    try {
        def user = jdbc.queryForMap(
            "SELECT user_id, phone, email, nickname FROM cas_user WHERE (username = ? OR phone = ? OR email = ?) AND status = 1",
            username, username, username
        )
        if (user) {
            return [
                username: user.user_id,
                email   : user.email,
                phone   : user.phone,
                name    : user.nickname ?: user.user_id
            ]
        }
    } catch (Exception e) {
        logger.warn("Passwordless 用户查询失败: username={}, error={}", username, e.message)
    }
    return null
}
