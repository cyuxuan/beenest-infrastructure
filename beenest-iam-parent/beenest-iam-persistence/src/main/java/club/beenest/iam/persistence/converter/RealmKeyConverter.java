package club.beenest.iam.persistence.converter;

import club.beenest.iam.core.model.RealmKeyModel;
import club.beenest.iam.persistence.entity.RealmKey;
import org.springframework.stereotype.Component;

/**
 * 域签名密钥实体/模型转换器。
 * <p>私钥字段在库中为密文,转换时原样传递,由上层负责解密。</p>
 */
@Component
public class RealmKeyConverter {

    /**
     * 实体转换为领域模型。
     *
     * @param entity 域密钥实体
     * @return 域密钥模型;入参为 null 时返回 null
     */
    public RealmKeyModel toModel(RealmKey entity) {
        if (entity == null) {
            return null;
        }
        return new RealmKeyModel(
                entity.getId(),
                entity.getRealmId(),
                entity.getKeyType(),
                entity.getPrivateKey(),
                entity.getPublicKey(),
                Boolean.TRUE.equals(entity.getActive()),
                entity.getActivatedTime(),
                entity.getRetiredTime()
        );
    }
}
