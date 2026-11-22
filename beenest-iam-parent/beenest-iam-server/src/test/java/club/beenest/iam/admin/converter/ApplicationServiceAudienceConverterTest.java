package club.beenest.iam.admin.converter;

import club.beenest.iam.admin.dto.ApplicationServiceCreateRequest;
import club.beenest.iam.admin.dto.ApplicationServiceResponse;
import club.beenest.iam.admin.dto.ApplicationServiceUpdateRequest;
import club.beenest.iam.persistence.entity.ApplicationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应用受众转换测试。
 * <p>受众在 API 层是列表、库内是逗号分隔单串,转换器负责两边互转。空列表与 null
 * 语义不同:null 表示本次不改动,空列表表示清空并回落到按应用编码派生;
 * 转换器若把空列表折成 null,动态 SQL 会跳过该列,清空操作落不了库。</p>
 */
class ApplicationServiceAudienceConverterTest {

    private final ApplicationServiceDtoConverter converter = new ApplicationServiceDtoConverter();

    @Test
    @DisplayName("新建时多个受众拼成逗号分隔单串")
    void joinsAudiencesOnCreate() {
        ApplicationService app = converter.toEntity(
                createRequest(List.of(" https://a.example.com/api ", "https://b.example.com/api")), 1L);

        assertEquals("https://a.example.com/api,https://b.example.com/api", app.getAudience());
    }

    @Test
    @DisplayName("新建时空白项被丢弃")
    void dropsBlankAudiencesOnCreate() {
        ApplicationService app = converter.toEntity(
                createRequest(Arrays.asList("https://a.example.com/api", "  ", null)), 1L);

        assertEquals("https://a.example.com/api", app.getAudience());
    }

    @Test
    @DisplayName("新建时未给受众则不写入,由签发端按应用编码派生")
    void keepsAudienceNullWhenCreateOmitsIt() {
        ApplicationService app = converter.toEntity(createRequest(null), 1L);

        assertEquals(null, app.getAudience());
    }

    @Test
    @DisplayName("读取时逗号分隔单串拆回列表")
    void splitsAudiencesOnResponse() {
        ApplicationServiceResponse response =
                converter.toResponse(storedApp("https://a.example.com/api, https://b.example.com/api"));

        assertEquals(List.of("https://a.example.com/api", "https://b.example.com/api"), response.audiences());
    }

    @Test
    @DisplayName("读取时未登记受众返回空列表")
    void returnsEmptyAudiencesWhenNotConfigured() {
        assertTrue(converter.toResponse(storedApp(null)).audiences().isEmpty());
        assertTrue(converter.toResponse(storedApp("  ")).audiences().isEmpty());
    }

    @Test
    @DisplayName("更新给出空列表时清空受众")
    void clearsAudienceOnEmptyUpdate() {
        ApplicationService app = storedApp("https://a.example.com/api");

        converter.applyUpdate(app, updateRequest(List.of()));

        assertEquals("", app.getAudience());
    }

    @Test
    @DisplayName("更新未给出受众字段时保留原值")
    void keepsAudienceWhenUpdateOmitsField() {
        ApplicationService app = storedApp("https://a.example.com/api");

        converter.applyUpdate(app, updateRequest(null));

        assertEquals("https://a.example.com/api", app.getAudience());
    }

    private static ApplicationService storedApp(String audience) {
        ApplicationService app = new ApplicationService();
        app.setId(7L);
        app.setRealmId(1L);
        app.setAppCode("drone-system");
        app.setAudience(audience);
        app.setAppName("无人机系统");
        return app;
    }

    private static ApplicationServiceCreateRequest createRequest(List<String> audiences) {
        return new ApplicationServiceCreateRequest("drone-system", audiences, "无人机系统",
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static ApplicationServiceUpdateRequest updateRequest(List<String> audiences) {
        return new ApplicationServiceUpdateRequest(null, audiences,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
