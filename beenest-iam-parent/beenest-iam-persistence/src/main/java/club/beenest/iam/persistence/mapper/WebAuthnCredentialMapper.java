package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.WebAuthnCredential;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Passkey 凭证 Mapper(iam_webauthn_credential)。
 * <p>删除一律为逻辑删除,保留的凭证标识用于对应审计日志;查询均排除已删除凭证。</p>
 */
public interface WebAuthnCredentialMapper {

    /**
     * 插入一条凭证。
     *
     * @param credential 待插入的凭证
     * @return 受影响行数;主键回填至 id
     */
    int insert(WebAuthnCredential credential);

    /**
     * 按凭证标识查询未删除凭证。
     *
     * @param credentialId 凭证标识(Base64URL)
     * @return 凭证;不存在返回 null
     */
    WebAuthnCredential selectByCredentialId(@Param("credentialId") String credentialId);

    /**
     * 查询某用户绑定的全部未删除凭证,按创建时间倒序。
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @return 凭证列表;无绑定时返回空列表
     */
    List<WebAuthnCredential> selectByUserId(@Param("realmId") Long realmId, @Param("userId") Long userId);

    /**
     * 按主键查询属于指定用户的未删除凭证。
     *
     * @param id     凭证主键
     * @param userId 用户主键
     * @return 凭证;不存在或不属于该用户时返回 null
     */
    WebAuthnCredential selectByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 按用户句柄查询未删除凭证,用于无用户名登录。
     *
     * @param realmId    身份域主键
     * @param userHandle 用户句柄(Base64URL)
     * @return 该句柄下的凭证列表;不存在时返回空列表
     */
    List<WebAuthnCredential> selectByUserHandle(@Param("realmId") Long realmId,
                                                @Param("userHandle") String userHandle);

    /**
     * 统计某用户已绑定的未删除凭证数量。
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @return 凭证数量
     */
    int countByUserId(@Param("realmId") Long realmId, @Param("userId") Long userId);

    /**
     * 断言成功后更新签名计数器、备份状态与最近使用时间。
     *
     * @param id          凭证主键
     * @param signCount   新的签名计数器
     * @param backupState 当前是否已备份
     * @return 受影响行数
     */
    int updateUsage(@Param("id") Long id,
                    @Param("signCount") Long signCount,
                    @Param("backupState") Boolean backupState);

    /**
     * 修改用户自定义的凭证名称。
     *
     * @param id          凭证主键
     * @param userId      用户主键,限定只能修改自己的凭证
     * @param displayName 新名称
     * @return 受影响行数
     */
    int updateDisplayName(@Param("id") Long id,
                          @Param("userId") Long userId,
                          @Param("displayName") String displayName);

    /**
     * 逻辑删除一把凭证。
     *
     * @param id     凭证主键
     * @param userId 用户主键,限定只能删除自己的凭证
     * @return 受影响行数
     */
    int softDelete(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 逻辑删除某用户的全部凭证,用于账号停用或密钥疑似泄露时整体作废。
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @return 受影响行数
     */
    int softDeleteByUserId(@Param("realmId") Long realmId, @Param("userId") Long userId);
}
