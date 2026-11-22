package club.beenest.iam.integration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 集成测试基类。
 * <p>用真实的 PostgreSQL 与 Redis 跑完整协议栈:单元测试用桩件替掉了授权存储与
 * Redis,恰恰盖不住"接口返回 200 但实际没生效"这类只在真实栈上才暴露的缺陷。</p>
 * <p><b>@SpringBootTest 与容器都由各测试类自己声明</b>:Testcontainers 的 JUnit 扩展
 * 在"声明它的类"的测试结束时停止容器——放在基类里,第一个测试类跑完容器就被停了,
 * 后面的类连不上数据库;而 @SpringBootTest 若只写在基类上,扩展的注册顺序会变成
 * 先建 Spring 上下文、后启容器,建上下文时拿不到数据库端口。各自声明还让每个
 * 测试类拥有独立的库,互不污染。</p>
 */
public abstract class IamIntegrationTestBase {

    /** 引导管理员口令 */
    protected static final String ADMIN_PASSWORD = "Integration#2026";

    /** 随机端口:测试自己拼根地址,不依赖自动配置的根地址 */
    @LocalServerPort
    private int port;

    @Autowired
    protected com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 原生 SQL 通道:验证数据库层约束(只追加触发器之类)时需要绕开应用层。 */
    @Autowired
    protected org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    protected TestRestTemplate rest;

    @BeforeEach
    void setUpRestTemplate() {
        // 不自动跟随重定向:断言的正是"未登录时跳登录页"这一跳
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        rest = new TestRestTemplate(new RestTemplateBuilder()
                .rootUri("http://localhost:" + port)
                .requestFactory(() -> factory));
    }

    /** 基础地址 */
    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * 解包统一响应体的 data 段。
     * <p>管理端接口返回 {code,message,data},协议端点(OAuth2)返回的是裸 JSON——
     * 取 data 时要认准是哪一种。</p>
     */
    protected com.fasterxml.jackson.databind.JsonNode dataOf(String body) throws Exception {
        return objectMapper.readTree(body).path("data");
    }

    /** JSON 请求头。 */
    protected HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
