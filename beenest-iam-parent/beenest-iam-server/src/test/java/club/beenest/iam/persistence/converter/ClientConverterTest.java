package club.beenest.iam.persistence.converter;

import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.persistence.entity.Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端转换器测试。
 * <p>准入白名单是准入判定的输入,转换环节一旦丢了字段,
 * 策略就会退化成"不限制"——而"不限制"看起来是正常状态,不会被发现。</p>
 */
class ClientConverterTest {

    private final ClientConverter converter = new ClientConverter();

    @Test
    @DisplayName("准入白名单与强制 MFA 从实体带到模型")
    void carriesAdmissionFields() {
        Client entity = new Client();
        entity.setAllowedLoginTypes("PASSWORD,SMS");
        entity.setRequireMfa(true);

        ClientModel model = converter.toModel(entity);

        assertEquals(Set.of("PASSWORD", "SMS"), model.allowedLoginTypes());
        assertTrue(model.requireMfa());
    }

    @Test
    @DisplayName("白名单为空表示不限制,不是空集合语义错误")
    void emptyWhitelistMeansUnrestricted() {
        Client entity = new Client();
        entity.setAllowedLoginTypes("");

        ClientModel model = converter.toModel(entity);

        assertTrue(model.allowedLoginTypes().isEmpty());
    }

    @Test
    @DisplayName("白名单字段未设置时按不限制处理")
    void unsetWhitelistMeansUnrestricted() {
        // DDL 是 NOT NULL DEFAULT '',走 SQL 时不可达;但"一旦可达就静默放开
        // 访问权限"是准入策略最不能出错的那个分支,值得钉死
        Client entity = new Client();

        ClientModel model = converter.toModel(entity);

        assertTrue(model.allowedLoginTypes().isEmpty());
    }

    @Test
    @DisplayName("强制 MFA 未设置时按未开启处理,不会凭空给客户端套上 MFA")
    void unsetRequireMfaMeansDisabled() {
        Client entity = new Client();
        // 实体不设置 requireMfa,保持 null

        ClientModel model = converter.toModel(entity);

        assertFalse(model.requireMfa());
    }

    @Test
    @DisplayName("强制 MFA 显式开启时带到模型")
    void explicitRequireMfaIsCarried() {
        Client entity = new Client();
        entity.setRequireMfa(Boolean.TRUE);

        ClientModel model = converter.toModel(entity);

        assertTrue(model.requireMfa());
    }
}
