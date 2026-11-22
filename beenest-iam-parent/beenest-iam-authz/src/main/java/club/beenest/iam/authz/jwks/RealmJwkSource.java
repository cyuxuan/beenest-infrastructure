package club.beenest.iam.authz.jwks;

import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.core.model.RealmKeyModel;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 域感知的 JWK 来源。
 * <p>同一实例服务全部身份域:按当前请求上下文返回该域的密钥集合,
 * 当前生效密钥携带私钥(供令牌签名),轮换期旧密钥仅发布公钥。</p>
 */
@Component
public class RealmJwkSource implements JWKSource<SecurityContext> {

    /** 无域上下文时的回落域标识 */
    private static final String DEFAULT_REALM_KEY = "default";

    private final RealmKeyService realmKeyService;
    private final RealmProvider realmProvider;

    public RealmJwkSource(RealmKeyService realmKeyService, RealmProvider realmProvider) {
        this.realmKeyService = realmKeyService;
        this.realmProvider = realmProvider;
    }

    @Override
    public List<JWK> get(JWKSelector jwkSelector, SecurityContext context) {
        // 1. 解析当前域:无上下文时回落默认域。
        //    JWKS 端点在过滤器链中可能先于域上下文过滤器执行,若此处直接返回空集合,
        //    外部客户端会拿到 {"keys":[]} 而无法验签;与客户端解析口径保持一致更有意义
        String realmKey = currentRealmKey();
        // 2. 按选择器过滤(kid/算法/用途),端点响应只输出公钥参数
        return jwksFor(realmKey).stream()
                .filter(jwk -> jwkSelector.getMatcher().matches(jwk))
                .toList();
    }

    /**
     * 取指定身份域的全部 JWK。
     * <p>供验签使用:业务接口路径中不含域标识,需按令牌 issuer 反查域后再取密钥。
     * 域已停用时返回空集合,验签环节据此拒绝该域此前签出的令牌。</p>
     *
     * @param realmKey 域标识
     * @return 该域当前生效密钥与轮换期旧密钥;域不存在或已停用时为空集合
     */
    public List<JWK> jwksFor(String realmKey) {
        // 1. 域必须存在且启用:停用的域不再接受任何令牌,否则停用只是界面上失效
        RealmModel realm = realmProvider.findByRealmKey(realmKey).orElse(null);
        if (realm == null || !realm.enabled()) {
            return List.of();
        }
        Long realmId = realm.id();
        // 2. 当前生效密钥必须存在(不存在则当场生成并落库)
        RealmKeyModel activeKey = realmKeyService.getOrCreateActiveKey(realmId);
        List<JWK> jwks = new ArrayList<>();
        jwks.add(toRsaJwk(activeKey, true));
        // 3. 轮换期旧密钥仅发布公钥
        for (RealmKeyModel old : realmKeyService.listKeys(realmId)) {
            if (!old.active()) {
                jwks.add(toRsaJwk(old, false));
            }
        }
        return jwks;
    }

    /**
     * 当前请求的域标识,无上下文时回落默认域(见 {@link #get} 的说明)。
     *
     * @return 域标识
     */
    private static String currentRealmKey() {
        RealmContext context = RealmContextHolder.get();
        return context == null ? DEFAULT_REALM_KEY : context.realmKey();
    }

    /**
     * 密钥模型转 RSA JWK;includePrivate 为真时携带私钥(仅当前生效密钥)。
     *
     * @param model          域密钥模型
     * @param includePrivate 是否携带私钥参数
     * @return 带 kid、签名用途与 RS256 算法标识的 RSA JWK
     */
    private RSAKey toRsaJwk(RealmKeyModel model, boolean includePrivate) {
        RSAKey.Builder builder = new RSAKey.Builder(realmKeyService.parsePublicKey(model.publicKey()))
                .keyID(kidOf(model))
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256);
        if (includePrivate && model.privateKey() != null) {
            builder.privateKey(realmKeyService.parsePrivateKey(model.privateKey()));
        }
        return builder.build();
    }

    /**
     * 计算密钥标识:公钥 DER 的 SHA-256 前 16 位十六进制,随轮换变化。
     *
     * @param model 域密钥模型
     * @return 形如 rk-xxxxxxxxxxxxxxxx 的 kid
     * @throws IllegalStateException PEM 内容非法或摘要算法不可用
     */
    private static String kidOf(RealmKeyModel model) {
        try {
            byte[] der = model.publicKey().replaceAll("-----(BEGIN|END) [A-Z ]+-----", "")
                    .replaceAll("\\s", "").getBytes(StandardCharsets.US_ASCII);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Base64.getDecoder().decode(new String(der)));
            StringBuilder hex = new StringBuilder("rk-");
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("kid 计算失败", e);
        }
    }
}
