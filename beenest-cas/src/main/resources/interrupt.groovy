import org.apereo.cas.interrupt.InterruptResponse

/**
 * CAS 认证中断 Groovy 脚本。
 * 在认证成功后检查是否需要中断（如强制改密、全局公告等）。
 */
def run(Object[] args) {
    def (authentication, registeredService, logger) = args

    // 检查是否需要强制改密
    def attributes = authentication.attributes
    def mustChangePassword = attributes.get("mustChangePassword")?.get(0)
    if (mustChangePassword == "true") {
        logger.info("用户 {} 需要强制修改密码，触发中断", authentication.principal)
        return new InterruptResponse(
            "您的密码已过期，请立即修改",
            ["links": ["修改密码": "/cas/account"]],
            false,  // ssoEnabled
            true    // block
        )
    }

    // 默认不中断
    return InterruptResponse.none()
}
