package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.UserCredential;
import org.apache.ibatis.annotations.Param;

/**
 * 用户凭证 Mapper(iam_user_credential 单表)。
 * <p>口令与 MFA 密钥以哈希或密文存储;查询排除逻辑删除行。</p>
 */
public interface UserCredentialMapper {

    /**
     * 查询用户某类型最近的启用凭证。
     *
     * @param userId         用户主键
     * @param credentialType 凭证类型
     * @return 凭证;无启用凭证返回 null
     */
    UserCredential selectByUserIdAndType(@Param("userId") Long userId, @Param("credentialType") String credentialType);

    /**
     * 查询用户某类型的最新凭证,不区分启用状态。
     * <p>MFA 绑定流程需读取 enabled=FALSE 的待确认凭证。</p>
     *
     * @param userId         用户主键
     * @param credentialType 凭证类型
     * @return 凭证;不存在返回 null
     */
    UserCredential selectLatestByUserIdAndType(@Param("userId") Long userId, @Param("credentialType") String credentialType);

    /**
     * 插入凭证,deleted 固定写入 FALSE。
     *
     * @param userCredential 凭证实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(UserCredential userCredential);

    /**
     * 停用用户某类型的全部启用凭证。
     * <p>重置口令时先停用旧凭证再插入新凭证,历史密文保留用于追溯。</p>
     *
     * @param userId         用户主键
     * @param credentialType 凭证类型
     * @return 受影响行数
     */
    int disableByUserIdAndType(@Param("userId") Long userId, @Param("credentialType") String credentialType);

    /**
     * 逻辑删除用户某类型下全部未启用的待确认凭证。
     * <p>重新绑定前清理此前的待确认行,已启用凭证不受影响。</p>
     *
     * @param userId         用户主键
     * @param credentialType 凭证类型
     * @return 受影响行数
     */
    int softDeletePendingByUserIdAndType(@Param("userId") Long userId, @Param("credentialType") String credentialType);

    /**
     * 统计域内已启用某类型凭证的去重用户数,用于 MFA 绑定率。
     *
     * @param realmId        身份域主键
     * @param credentialType 凭证类型(如 TOTP)
     * @return 用户数
     */
    long countEnabledUsersByType(@Param("realmId") Long realmId, @Param("credentialType") String credentialType);
}
