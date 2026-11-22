package club.beenest.iam.authz.principal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 认证主体(登录成功后挂在 Authentication 上的用户快照)。
 * <p>字段全部为简单类型,可被 Jackson 2 序列化往返(Redis 授权存储依赖此特性)。
 * 声明为 final:序列化层对非 final 类型才写多态类型标记,本类因此以纯 JSON 形态存取,
 * 无需进入反序列化白名单。</p>
 */
public final class IamUserPrincipal implements Principal, Serializable {

    /** 用户主键 */
    private final Long userId;
    /** 所属身份域主键 */
    private final Long realmId;
    /** 身份域标识 */
    private final String realmKey;
    /** 登录名 */
    private final String username;
    /** 昵称 */
    private final String nickname;
    /** 令牌版本(全端下线即递增) */
    private final int tokenVersion;
    /** 权限码集合(已按权限公式计算完成) */
    private final List<String> authorities;
    /** 扩展属性 */
    private final Map<String, String> attributes;
    /**
     * 计算这批权限时的权限代次。
     * <p>主体是授权决策的快照,携带决策时的代次,会话据此判断权限是否过期:
     * 当前代次变化即重算。令牌路径不需要它(令牌每次签发都是现算)。</p>
     */
    private final long permissionEpoch;
    /**
     * 本次会话的认证方式(LoginMethod 的存储码)。
     * <p>为准入判定提供依据:客户端可限定认证方式,需要知道当前会话的建立方式。
     * 存量主体没有该字段,读出为 null,判定按方式未知处理,要求补验而非放行。</p>
     */
    private final String loginType;

    /** 便捷构造:权限代次未知(仅内存中的临时主体,不具备会话续期能力)。 */
    public IamUserPrincipal(Long userId, Long realmId, String realmKey, String username, String nickname,
                            int tokenVersion, List<String> authorities, Map<String, String> attributes) {
        this(userId, realmId, realmKey, username, nickname, tokenVersion, authorities, attributes,
                EPOCH_UNKNOWN);
    }

    /** 权限代次未知 */
    public static final long EPOCH_UNKNOWN = -1L;

    /** 便捷构造:认证方式未知(授权码换票等不经过登录的路径)。 */
    public IamUserPrincipal(Long userId, Long realmId, String realmKey, String username, String nickname,
                            int tokenVersion, List<String> authorities, Map<String, String> attributes,
                            long permissionEpoch) {
        this(userId, realmId, realmKey, username, nickname, tokenVersion, authorities, attributes,
                permissionEpoch, null);
    }

    @JsonCreator
    public IamUserPrincipal(@JsonProperty("userId") Long userId,
                            @JsonProperty("realmId") Long realmId,
                            @JsonProperty("realmKey") String realmKey,
                            @JsonProperty("username") String username,
                            @JsonProperty("nickname") String nickname,
                            @JsonProperty("tokenVersion") int tokenVersion,
                            @JsonProperty("authorities") List<String> authorities,
                            @JsonProperty("attributes") Map<String, String> attributes,
                            @JsonProperty("permissionEpoch") long permissionEpoch,
                            @JsonProperty("loginType") String loginType) {
        this.userId = userId;
        this.realmId = realmId;
        this.realmKey = realmKey;
        this.username = username;
        this.nickname = nickname;
        this.tokenVersion = tokenVersion;
        this.authorities = List.copyOf(authorities == null ? List.of() : authorities);
        this.attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        this.permissionEpoch = permissionEpoch;
        this.loginType = loginType;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getRealmId() {
        return realmId;
    }

    public String getRealmKey() {
        return realmKey;
    }

    public String getUsername() {
        return username;
    }

    public String getNickname() {
        return nickname;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public List<String> getAuthorities() {
        return authorities;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    /** 计算这批权限时的权限代次。 */
    public long getPermissionEpoch() {
        return permissionEpoch;
    }

    /** 本次会话的认证方式;null 表示未知(存量会话)。 */
    public String getLoginType() {
        return loginType;
    }

    /**
     * Principal 接口要求的主体名。
     * <p>取值即登录名,序列化时忽略:它是派生字段,输出会造成与 username 重复且无法反序列化。</p>
     */
    @JsonIgnore
    @Override
    public String getName() {
        return username;
    }
}
