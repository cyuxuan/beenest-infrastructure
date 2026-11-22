package club.beenest.iam.federation.registry;

import club.beenest.iam.core.spi.federation.IdentityProvider;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.core.spi.federation.IdentityProviderFactory;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外置扩展包装载器测试。
 * <p>第三方包是外部输入:它坏了、版本对不上、服务声明写错了,都可能让装载过程
 * 抛出非受检异常。这些情况都不能把平台带崩——起不来就进不去管理界面,
 * 连"把坏包换掉"这件事都做不了。</p>
 */
class ExternalProviderLoaderTest {

    /** 提供方工厂的服务声明文件名(与 SPI 的全限定名对应)。 */
    private static final String SERVICE_FILE =
            "META-INF/services/club.beenest.iam.core.spi.federation.IdentityProviderFactory";

    @Test
    @DisplayName("服务声明写错的扩展包:跳过它,不影响其它扩展包,更不影响平台启动")
    void brokenServiceDeclarationDoesNotBreakLoading() throws Exception {
        Path dir = Files.createTempDirectory("iam-external-providers");
        // 服务声明指向一个不存在的类:ServiceLoader 会抛 ServiceConfigurationError(Error,不是 Exception)
        writeJar(dir.resolve("broken-provider.jar"), SERVICE_FILE,
                "com.example.NotExistFactory\n");
        writeStubJar(dir.resolve("good-provider.jar"));

        IdentityProviderRegistry registry = new IdentityProviderRegistry(List.of());
        ExternalProviderLoader loader = new ExternalProviderLoader(dir.toString(), registry);

        assertDoesNotThrow(loader::load, "坏的扩展包不能把平台带崩");
        assertTrue(registry.getFactory("stub-external").isPresent(),
                "坏包被跳过后,好包仍然要装载成功");
    }

    @Test
    @DisplayName("扩展包目录不存在或为空:跳过装载,不报错")
    void missingOrEmptyDirectoryIsSkipped() {
        IdentityProviderRegistry registry = new IdentityProviderRegistry(List.of());

        ExternalProviderLoader missing = new ExternalProviderLoader("/tmp/not-exist-provider-dir", registry);
        assertDoesNotThrow(missing::load);

        ExternalProviderLoader blank = new ExternalProviderLoader("", registry);
        assertDoesNotThrow(blank::load);
    }

    /**
     * 造一个只含服务声明的坏 jar。
     */
    private static void writeJar(Path jar, String entryName, String content) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(entryName));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    /**
     * 造一个装着测试桩工厂的 jar:与真实的第三方扩展包一样,jar 里只有工厂类与服务声明。
     */
    private static void writeStubJar(Path jar) throws Exception {
        String className = StubProviderFactory.class.getName();
        String classFile = className.replace('.', '/') + ".class";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(SERVICE_FILE));
            out.write((className + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(classFile));
            URL location = StubProviderFactory.class.getProtectionDomain().getCodeSource().getLocation();
            try (InputStream in = location.openStream()) {
                out.write(in.readAllBytes());
            }
            out.closeEntry();
        }
    }

    /** 桩工厂:外置扩展包里的工厂必须能被无参构造出来。 */
    public static final class StubProviderFactory
            implements IdentityProviderFactory<IdentityProvider<IdentityProviderConfig>> {

        @Override
        public String getProviderId() {
            return "stub-external";
        }

        @Override
        public String getName() {
            return "外置测试提供方";
        }

        @Override
        public List<ProviderConfigProperty> getConfigProperties() {
            return List.of(new ProviderConfigProperty("clientId", "客户端 ID",
                    ProviderConfigProperty.ConfigType.STRING, true, false, null, ""));
        }

        @Override
        public IdentityProvider<IdentityProviderConfig> create(IdentityProviderConfig config) {
            throw new UnsupportedOperationException("测试不需要真实实例");
        }
    }
}
