package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.UserFederatedIdentity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户-外部身份绑定 Mapper(iam_user_federated_identity 单表)。
 * <p>一条绑定把外部身份指向本地账号,解绑为逻辑删除。</p>
 */
public interface UserFederatedIdentityMapper {

    /**
     * 按提供方与外部标识查询未删除绑定。
     *
     * @param idpId      提供方主键
     * @param externalId 外部身份标识
     * @return 绑定;不存在返回 null
     */
    UserFederatedIdentity selectByIdpAndExternalId(@Param("idpId") Long idpId, @Param("externalId") String externalId);

    /**
     * 按用户与提供方查询未删除绑定。
     *
     * @param userId 用户主键
     * @param idpId  提供方主键
     * @return 绑定;不存在返回 null
     */
    UserFederatedIdentity selectByUserIdAndIdpId(@Param("userId") Long userId, @Param("idpId") Long idpId);

    /**
     * 查询用户全部未删除绑定。
     * <p>令牌签发时据此把渠道身份写入 {@code idp_identities},应用侧无需另查 IAM。</p>
     *
     * @param userId 用户主键
     * @return 绑定列表;无绑定时返回空列表
     */
    List<UserFederatedIdentity> selectByUserId(@Param("userId") Long userId);

    /**
     * 插入绑定,deleted 固定写入 FALSE。
     *
     * @param userFederatedIdentity 绑定实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(UserFederatedIdentity userFederatedIdentity);

    /**
     * 按提供方逻辑删除全部绑定,删除提供方时调用;本地账号保留。
     *
     * @param idpId 提供方主键
     * @return 被解绑的绑定条数
     */
    int softDeleteByIdpId(@Param("idpId") Long idpId);
}
