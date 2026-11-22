package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.security.IamRealmKeyCrypto;
import club.beenest.iam.core.model.RealmKeyModel;
import club.beenest.iam.core.spi.provider.RealmKeyProvider;
import club.beenest.iam.persistence.converter.RealmKeyConverter;
import club.beenest.iam.persistence.entity.RealmKey;
import club.beenest.iam.persistence.mapper.RealmKeyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 域签名密钥存储(iam_realm_key)。
 * <p>私钥加密后落库,读取时解密;存量明文记录在读取时回写为密文,回写失败只记日志。</p>
 */
@Component
public class IamRealmKeyManager implements RealmKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(IamRealmKeyManager.class);

    private final RealmKeyMapper realmKeyMapper;
    private final RealmKeyConverter realmKeyConverter;
    private final IamRealmKeyCrypto keyCrypto;

    public IamRealmKeyManager(RealmKeyMapper realmKeyMapper, RealmKeyConverter realmKeyConverter,
                              IamRealmKeyCrypto keyCrypto) {
        this.realmKeyMapper = realmKeyMapper;
        this.realmKeyConverter = realmKeyConverter;
        this.keyCrypto = keyCrypto;
    }

    @Override
    public Optional<RealmKeyModel> findActive(Long realmId) {
        return Optional.ofNullable(realmKeyConverter.toModel(realmKeyMapper.selectActiveByRealmId(realmId)))
                .map(model -> decrypt(realmId, model));
    }

    @Override
    public List<RealmKeyModel> listByRealm(Long realmId) {
        return realmKeyMapper.selectByRealmId(realmId).stream()
                .map(realmKeyConverter::toModel)
                .map(model -> decrypt(realmId, model))
                .toList();
    }

    /** 新增密钥,私钥加密后落库。 */
    @Override
    public void save(RealmKeyModel realmKey) {
        RealmKey entity = new RealmKey();
        entity.setRealmId(realmKey.realmId());
        entity.setKeyType(realmKey.keyType());
        // 私钥加密落库:明文私钥不进入数据库
        entity.setPrivateKey(keyCrypto.encrypt(realmKey.realmId(), realmKey.privateKey()));
        entity.setPublicKey(realmKey.publicKey());
        entity.setActive(realmKey.active());
        entity.setActivatedTime(realmKey.activatedTime());
        entity.setRetiredTime(realmKey.retiredTime());
        realmKeyMapper.insert(entity);
    }

    @Override
    public void deactivateByRealm(Long realmId) {
        realmKeyMapper.deactivateByRealmId(realmId);
    }

    /**
     * 读取时解密,存量明文记录顺带回写为密文。
     *
     * @param realmId 身份域主键
     * @param model 密钥模型
     * @return 私钥为明文的密钥模型
     */
    private RealmKeyModel decrypt(Long realmId, RealmKeyModel model) {
        if (model.privateKey() == null) {
            return model;
        }
        if (keyCrypto.isEncrypted(model.privateKey())) {
            String plain = keyCrypto.decrypt(realmId, model.privateKey());
            return new RealmKeyModel(model.id(), model.realmId(), model.keyType(), plain,
                    model.publicKey(), model.active(), model.activatedTime(), model.retiredTime());
        }
        log.info("身份域签名私钥仍为明文存储,已就地升级为密文: realmId={}", realmId);
        upgradeToEncrypted(realmId, model);
        return model;
    }

    /**
     * 把存量明文私钥改写为密文,失败只记日志。
     *
     * @param realmId 身份域主键
     * @param model 密钥模型
     */
    private void upgradeToEncrypted(Long realmId, RealmKeyModel model) {
        try {
            RealmKey entity = new RealmKey();
            entity.setId(model.id());
            entity.setPrivateKey(keyCrypto.encrypt(realmId, model.privateKey()));
            realmKeyMapper.updatePrivateKey(entity);
        } catch (Exception e) {
            log.warn("身份域签名私钥就地升级失败,不影响本次使用: realmId={}, error={}", realmId, e.getMessage());
        }
    }
}
