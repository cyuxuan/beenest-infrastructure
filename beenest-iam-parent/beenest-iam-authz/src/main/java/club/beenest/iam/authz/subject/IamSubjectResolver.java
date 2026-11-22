package club.beenest.iam.authz.subject;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.SubjectMode;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.core.spi.provider.RealmProvider;
import org.springframework.stereotype.Component;

/**
 * OIDC 主体标识({@code sub})解析器。
 * <p>sub 是应用侧关联自然人身份的锚点,须在账号生命周期内保持不变:用户主键不可变,
 * 登录名会随改名、账号迁移、跨系统合并变化。策略两级可配置,身份域给默认值(默认
 * 登录名,存量行为不变),客户端可单独覆盖,切换可逐应用推进。</p>
 * <p>所有签发路径(ID Token、Access Token、userinfo、后通道登出令牌)都必须经此处解析,
 * 保证 sub 同源,应用侧 logout_token 与 id_token 的 sub 校验一致。</p>
 */
@Component
public class IamSubjectResolver {

    private final RealmProvider realmProvider;
    private final ClientProvider clientProvider;

    public IamSubjectResolver(RealmProvider realmProvider, ClientProvider clientProvider) {
        this.realmProvider = realmProvider;
        this.clientProvider = clientProvider;
    }

    /**
     * 按客户端解析主体标识。
     *
     * @param user      登录主体(含用户主键与登录名)
     * @param clientKey 目标应用的 client_id;为空时按域策略解析
     * @return sub 的值
     */
    public String resolve(IamUserPrincipal user, String clientKey) {
        if (user == null) {
            return null;
        }
        return subjectOf(user, resolveMode(user, clientKey));
    }

    /**
     * 已持有客户端模型时的解析(避免重复查库),供登出广播逐应用投递使用。
     *
     * @param user   登录主体;为空返回 null(只带 sid 的登出令牌)
     * @param client 目标应用模型
     * @return sub 的值
     */
    public String resolve(IamUserPrincipal user, ClientModel client) {
        if (user == null) {
            return null;
        }
        SubjectMode realmMode = realmMode(user);
        return subjectOf(user, client == null ? realmMode : client.resolveSubjectMode(realmMode));
    }

    /**
     * 解析生效的主体策略:客户端显式配置优先,未配置继承所属域。
     *
     * @param user      登录主体
     * @param clientKey 目标应用的 client_id;为空时按域策略解析
     * @return 生效的主体策略
     */
    public SubjectMode resolveMode(IamUserPrincipal user, String clientKey) {
        SubjectMode realmMode = realmMode(user);
        if (clientKey == null || clientKey.isBlank()) {
            return realmMode;
        }
        return clientProvider.findByClientKey(user.getRealmId(), clientKey)
                .map(client -> client.resolveSubjectMode(realmMode))
                .orElse(realmMode);
    }

    /**
     * 读取域级主体策略。
     * <p>域不存在时回落登录名,签发链路不因配置缺失而中断。</p>
     *
     * @param user 登录主体
     * @return 域级主体策略
     */
    private SubjectMode realmMode(IamUserPrincipal user) {
        return realmProvider.findByRealmKey(user.getRealmKey())
                .map(RealmModel::subjectMode)
                .orElse(SubjectMode.LOGIN_NAME);
    }

    /**
     * 按策略取主体值。
     *
     * @param user 登录主体
     * @param mode 生效的主体策略;USER_ID 且主键存在时取不可变主键,其余情况取登录名
     * @return sub 的值
     */
    private static String subjectOf(IamUserPrincipal user, SubjectMode mode) {
        if (mode == SubjectMode.USER_ID && user.getUserId() != null) {
            return String.valueOf(user.getUserId());
        }
        return user.getUsername();
    }
}
