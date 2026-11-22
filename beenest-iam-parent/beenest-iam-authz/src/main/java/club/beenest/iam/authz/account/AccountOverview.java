package club.beenest.iam.authz.account;

import club.beenest.iam.authz.rest.dto.WebAuthnCredentialResponse;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户中心首页数据。
 * <p>把"我是谁、我有哪些凭证、我授权了谁、我在哪登录"四类信息一次性装配好,
 * 页面直接渲染,不再二次请求。</p>
 *
 * @param profile        账号资料
 * @param credentials    登录凭证概况
 * @param consents       已授权的应用
 * @param sessions       登录设备
 * @param pendingActions 未完成的账户待办(动作代码)
 */
public record AccountOverview(
        Profile profile,
        Credentials credentials,
        List<ConsentItem> consents,
        List<SessionItem> sessions,
        List<String> pendingActions
) {

    /**
     * 账号资料。
     *
     * @param username      登录名
     * @param nickname      昵称
     * @param email         邮箱
     * @param emailVerified 邮箱是否已验证
     * @param phone         手机号
     * @param phoneVerified 手机号是否已验证
     * @param realmKey      所属身份域标识
     * @param userType      用户类型
     */
    public record Profile(
            String username,
            String nickname,
            String email,
            boolean emailVerified,
            String phone,
            boolean phoneVerified,
            String realmKey,
            String userType
    ) {
    }

    /**
     * 登录凭证概况。
     *
     * @param passwordSet            是否已设置本地口令
     * @param totpEnabled            动态口令是否已启用
     * @param recoveryCodesRemaining 剩余恢复码数量
     * @param passkeyAvailable       Passkey 能力是否可用
     * @param passkeys               已绑定的 Passkey
     */
    public record Credentials(
            boolean passwordSet,
            boolean totpEnabled,
            int recoveryCodesRemaining,
            boolean passkeyAvailable,
            List<WebAuthnCredentialResponse> passkeys
    ) {

        /**
         * 已启用的第二因素数量(动态口令 + Passkey),用于页面提示"仅口令登录"。
         *
         * @return 第二因素数量
         */
        public int secondFactorCount() {
            return (totpEnabled ? 1 : 0) + (passkeys == null ? 0 : passkeys.size());
        }
    }

    /**
     * 一条授权记录。
     *
     * @param clientKey   客户端标识
     * @param clientName  客户端展示名;客户端已删除时为 null
     * @param scopes      已授予的 scope
     * @param grantedTime 首次授予时间;记录缺失时为 null
     */
    public record ConsentItem(
            String clientKey,
            String clientName,
            List<ScopeItem> scopes,
            LocalDateTime grantedTime
    ) {
    }

    /**
     * 一条 scope 及其展示名。
     *
     * @param code  scope 原始标识
     * @param label 展示名;无对应文案时与 code 相同
     */
    public record ScopeItem(String code, String label) {
    }

    /**
     * 一台已登录设备。
     *
     * @param sid           会话标识
     * @param loginType     登录方式
     * @param ip            登录 IP
     * @param userAgent     客户端标识
     * @param createdTime   登录时间
     * @param lastActiveTime 最近活跃时间
     * @param current       是否为当前请求所用会话
     */
    public record SessionItem(
            String sid,
            String loginType,
            String ip,
            String userAgent,
            LocalDateTime createdTime,
            LocalDateTime lastActiveTime,
            boolean current
    ) {
    }
}
