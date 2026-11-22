package club.beenest.iam.persistence.converter;

import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.model.SubjectMode;
import club.beenest.iam.persistence.entity.Client;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 客户端实体/模型转换器。
 * <p>列表字段以逗号分隔存储;空值按 DDL 默认值回落,subjectMode 为空表示继承域策略。</p>
 */
@Component
public class ClientConverter {

    /**
     * 实体转换为领域模型。
     *
     * @param client 客户端实体
     * @return 客户端模型;入参为 null 时返回 null
     */
    public ClientModel toModel(Client client) {
        if (client == null) {
            return null;
        }
        ClientModel.ClientType clientType =
                "PUBLIC".equals(client.getClientType())
                        ? ClientModel.ClientType.PUBLIC
                        : ClientModel.ClientType.CONFIDENTIAL;
        return new ClientModel(
                client.getId(),
                client.getRealmId(),
                client.getClientKey(),
                client.getClientName(),
                clientType,
                client.getClientSecret(),
                splitToSet(client.getRedirectUris()),
                splitToSet(client.getPostLogoutRedirectUris()),
                client.getBackchannelLogoutUri(),
                // 空值按"复用"处理,与 DDL 默认值一致
                !Boolean.FALSE.equals(client.getReuseRefreshTokens()),
                splitToSet(client.getScopes()),
                !Boolean.FALSE.equals(client.getRequirePkce()),
                Boolean.TRUE.equals(client.getRequireConsent()),
                Boolean.TRUE.equals(client.getEnabled()),
                // 空表示继承域策略:保留 null,不回落到枚举
                client.getSubjectMode() == null || client.getSubjectMode().isBlank()
                        ? null : SubjectMode.parse(client.getSubjectMode()),
                client.getAccessTokenTtl(),
                client.getRefreshTokenTtl(),
                // 授权类型为空时按"授权码 + 刷新"处理,与 DDL 默认值一致
                grantTypesOf(client.getGrantTypes()),
                // 准入白名单:空串表示不限制,与 DDL 默认值一致
                splitToSet(client.getAllowedLoginTypes()),
                // 强制 MFA 为 opt-in 开关,空值按"未开启"处理,与 DDL 默认值 FALSE 一致
                // 不能用 !Boolean.FALSE.equals(...),该写法会把空值变成 true
                Boolean.TRUE.equals(client.getRequireMfa()),
                client.getServiceAccountUserId()
        );
    }

    /**
     * 解析授权类型集合。
     * <p>空值回落为"授权码 + 刷新令牌",与 DDL 默认值一致。</p>
     *
     * @param value 逗号分隔的授权类型
     * @return 授权类型集合;空值返回 authorization_code 与 refresh_token
     */
    public Set<String> grantTypesOf(String value) {
        Set<String> types = splitToSet(value);
        return types.isEmpty() ? Set.of("authorization_code", "refresh_token") : types;
    }

    /**
     * 逗号分隔字符串拆为集合。
     *
     * @param value 逗号分隔字符串
     * @return 去空白后的不可变集合;空值返回空集合
     */
    public Set<String> splitToSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 集合拼接为逗号分隔字符串。
     *
     * @param values 字符串集合
     * @return 保持传入顺序的逗号分隔字符串;空集合或全为空白时返回 null
     */
    public String toCommaSeparated(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(","));
    }
}
