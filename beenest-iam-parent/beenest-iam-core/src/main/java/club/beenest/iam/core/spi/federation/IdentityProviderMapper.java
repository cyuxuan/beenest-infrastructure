package club.beenest.iam.core.spi.federation;

import club.beenest.iam.core.model.UserModel;

/**
 * 身份提供方属性映射器 SPI。
 * <p>回调换回外部身份后,把外部用户属性(昵称/头像/邮箱等)写入本地用户档案;
 * 同一提供方可叠加多个映射器。</p>
 */
public interface IdentityProviderMapper {

    /**
     * 将外部身份属性导入本地用户。
     *
     * @param context 外部身份上下文
     * @param user    待写入属性的本地用户快照
     */
    void importIdentity(BrokeredIdentityContext context, UserModel user);

    /**
     * 声明该映射器适用的提供方类型。
     *
     * @return 提供方类型标识数组,如 wechat / oidc
     */
    String[] getCompatibleProviders();
}
