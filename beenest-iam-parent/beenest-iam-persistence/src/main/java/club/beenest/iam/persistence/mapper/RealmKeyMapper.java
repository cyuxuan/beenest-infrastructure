package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.RealmKey;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 域签名密钥 Mapper(iam_realm_key 单表)。
 * <p>每域仅一把生效密钥;私钥列存密文;查询排除逻辑删除行。</p>
 */
public interface RealmKeyMapper {

    /**
     * 查询域当前生效的密钥,多条时取主键最大的一条。
     *
     * @param realmId 身份域主键
     * @return 密钥;无生效密钥返回 null
     */
    RealmKey selectActiveByRealmId(@Param("realmId") Long realmId);

    /**
     * 查询域全部未删除密钥,含轮换中的旧密钥,按主键倒序。
     *
     * @param realmId 身份域主键
     * @return 密钥列表;无密钥时返回空列表
     */
    List<RealmKey> selectByRealmId(@Param("realmId") Long realmId);

    /**
     * 插入密钥。
     *
     * @param realmKey 密钥实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(RealmKey realmKey);

    /**
     * 退役域内全部生效密钥,轮换的第一步。
     * <p>旧密钥行保留,置 active=FALSE 并记录退役时间,JWKS 仍发布其公钥供已签发令牌验签。</p>
     *
     * @param realmId 身份域主键
     * @return 受影响行数
     */
    int deactivateByRealmId(@Param("realmId") Long realmId);

    /**
     * 就地改写私钥密文,用于存量明文升级为密文。
     * <p>只更新私钥列,其余字段保持不变。</p>
     *
     * @param realmKey 密钥实体,id 与 privateKey 必填
     * @return 受影响行数
     */
    int updatePrivateKey(RealmKey realmKey);
}
