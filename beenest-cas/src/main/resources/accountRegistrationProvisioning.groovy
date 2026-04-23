import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource
import java.util.UUID

/**
 * CAS 用户注册 Provisioning（Groovy 脚本）。
 * <p>
 * CAS 注册 Webflow 完成后调用此脚本，将用户写入 cas_user 表。
 * 支持用户名/手机号/邮箱注册，自动检查唯一性并执行账户合并。
 */
def run(Object[] args) {
    def (registrationRequest, logger) = args
    def dataSource = applicationContext.getBean("dataSource", DataSource.class)
    def jdbc = new JdbcTemplate(dataSource)

    // 1. 检查用户是否已存在（手机号/邮箱/用户名）
    def phone = registrationRequest.getProperty("phone")?.toString()
    def email = registrationRequest.getProperty("email")?.toString()
    def username = registrationRequest.getProperty("username")?.toString()

    if (phone) {
        def existing = jdbc.queryForList("SELECT user_id FROM cas_user WHERE phone = ? AND status != 4", phone)
        if (existing) {
            logger.info("注册用户已存在（手机号匹配）: phone={}, userId={}", phone, existing[0].user_id)
            return existing[0].user_id
        }
    }
    if (email) {
        def existing = jdbc.queryForList("SELECT user_id FROM cas_user WHERE email = ? AND status != 4", email)
        if (existing) {
            logger.info("注册用户已存在（邮箱匹配）: email={}, userId={}", email, existing[0].user_id)
            return existing[0].user_id
        }
    }
    if (username) {
        def existing = jdbc.queryForList("SELECT user_id FROM cas_user WHERE username = ? AND status != 4", username)
        if (existing) {
            logger.info("注册用户已存在（用户名匹配）: username={}, userId={}", username, existing[0].user_id)
            return existing[0].user_id
        }
    }

    // 2. 创建新用户
    def userId = "U" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase()
    def passwordHash = registrationRequest.getProperty("password")?.toString() ?: ""
    def nickname = registrationRequest.getProperty("nickname")?.toString() ?: username ?: phone

    jdbc.update("""
        INSERT INTO cas_user (user_id, user_type, identity, source, login_type,
                              username, nickname, phone, email, password_hash,
                              phone_verified, email_verified, status, failed_login_count, token_version)
        VALUES (?, 'CUSTOMER', NULL, 'WEB', 'USERNAME_PASSWORD', ?, ?, ?, ?, ?, ?, ?, 1, 0, 1)
    """, userId, username, nickname, phone, email, passwordHash,
         phone != null && !phone.isEmpty(), email != null && !email.isEmpty())

    logger.info("注册新用户: userId={}, username={}, phone={}", userId, username, phone)
    return userId
}
