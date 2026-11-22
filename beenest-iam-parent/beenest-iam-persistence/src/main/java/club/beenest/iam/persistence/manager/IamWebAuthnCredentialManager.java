package club.beenest.iam.persistence.manager;

import club.beenest.iam.persistence.entity.WebAuthnCredential;
import club.beenest.iam.persistence.mapper.WebAuthnCredentialMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Passkey 凭证存储(iam_webauthn_credential)。
 * <p>只负责持久化与参数兜底,公钥还原、签名验签与计数器比对在 authz 层完成。</p>
 */
@Component
public class IamWebAuthnCredentialManager {

    private final WebAuthnCredentialMapper mapper;

    public IamWebAuthnCredentialManager(WebAuthnCredentialMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 保存一把新绑定的凭证。
     *
     * @param credential 凭证实体;必须已填 realmId / userId / credentialId / credentialData
     * @return 保存后的凭证(主键已回填)
     */
    public WebAuthnCredential create(WebAuthnCredential credential) {
        mapper.insert(credential);
        return credential;
    }

    /**
     * 按凭证标识查询。
     *
     * @param credentialId 凭证标识(Base64URL)
     * @return 凭证;不存在返回 null
     */
    public WebAuthnCredential findByCredentialId(String credentialId) {
        if (credentialId == null || credentialId.isBlank()) {
            return null;
        }
        return mapper.selectByCredentialId(credentialId);
    }

    /**
     * 查询某用户的全部凭证。
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @return 凭证列表;无绑定时为空列表
     */
    public List<WebAuthnCredential> listByUserId(Long realmId, Long userId) {
        if (realmId == null || userId == null) {
            return List.of();
        }
        return mapper.selectByUserId(realmId, userId);
    }

    /**
     * 查询属于指定用户的凭证。
     *
     * @param id 凭证主键
     * @param userId 用户主键
     * @return 凭证;不存在或不属于该用户时返回 null
     */
    public WebAuthnCredential findOwned(Long id, Long userId) {
        if (id == null || userId == null) {
            return null;
        }
        return mapper.selectByIdAndUserId(id, userId);
    }

    /**
     * 按用户句柄查询凭证(无用户名登录)。
     *
     * @param realmId 身份域主键
     * @param userHandle 用户句柄(Base64URL)
     * @return 该句柄下的凭证列表;不存在时为空列表
     */
    public List<WebAuthnCredential> listByUserHandle(Long realmId, String userHandle) {
        if (realmId == null || userHandle == null || userHandle.isBlank()) {
            return List.of();
        }
        return mapper.selectByUserHandle(realmId, userHandle);
    }

    /**
     * 取该用户已存在的用户句柄(绑第二把钥匙时沿用,保证同一账号只有一个句柄)。
     *
     * @param realmId 身份域主键
     * @param userId 用户主键
     * @return 句柄;该用户尚未绑定任何凭证时返回 null
     */
    public String existingUserHandle(Long realmId, Long userId) {
        List<WebAuthnCredential> existing = listByUserId(realmId, userId);
        return existing.isEmpty() ? null : existing.get(0).getUserHandle();
    }

    /**
     * 统计用户已绑定的凭证数量。
     *
     * @param realmId 身份域主键
     * @param userId 用户主键
     * @return 数量
     */
    public int countByUserId(Long realmId, Long userId) {
        if (realmId == null || userId == null) {
            return 0;
        }
        return mapper.countByUserId(realmId, userId);
    }

    /**
     * 断言成功后更新签名计数器与最近使用时间。
     *
     * @param id 凭证主键
     * @param signCount 新的签名计数器
     * @param backupState 当前是否已备份;null 表示不修改
     */
    public void touchUsage(Long id, Long signCount, Boolean backupState) {
        mapper.updateUsage(id, signCount, backupState);
    }

    /**
     * 修改凭证名称。
     *
     * @param id 凭证主键
     * @param userId 用户主键
     * @param displayName 新名称
     * @return true 表示改名成功;凭证不存在或不属于该用户时返回 false
     */
    public boolean rename(Long id, Long userId, String displayName) {
        return mapper.updateDisplayName(id, userId, displayName) > 0;
    }

    /**
     * 删除一把凭证(逻辑删除)。
     *
     * @param id 凭证主键
     * @param userId 用户主键
     * @return true 表示删除成功;凭证不存在或不属于该用户时返回 false
     */
    public boolean delete(Long id, Long userId) {
        return mapper.softDelete(id, userId) > 0;
    }

    /**
     * 作废某用户的全部凭证。
     *
     * @param realmId 身份域主键
     * @param userId 用户主键
     * @return 被作废的数量
     */
    public int deleteAll(Long realmId, Long userId) {
        if (realmId == null || userId == null) {
            return 0;
        }
        return mapper.softDeleteByUserId(realmId, userId);
    }
}
