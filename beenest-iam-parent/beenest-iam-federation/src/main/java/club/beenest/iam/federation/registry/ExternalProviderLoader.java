package club.beenest.iam.federation.registry;

import club.beenest.iam.core.spi.federation.IdentityProviderFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * 外置身份提供方扩展包装载器。
 * <p>第三方提供方以独立 jar 交付:把 jar 放进约定目录、重启服务即可生效,
 * 既不用改本项目的 Maven 依赖,也不用重新打包服务端。jar 用服务声明
 * (META-INF/services)列出工厂实现类,平台逐个装载。</p>
 * <p>每个 jar 使用独立的类加载器:不同扩展包可能各自携带一份依赖版本,
 * 共用加载器会互相污染。</p>
 * <p>单个扩展包装载失败只记日志、不阻断启动,其余扩展包照常装载。</p>
 * <p>扩展包生效需要重启服务:类加载后无法卸载,运行中替换 jar 内容不会生效。</p>
 */
@Component
public class ExternalProviderLoader {

    private static final Logger log = LoggerFactory.getLogger(ExternalProviderLoader.class);

    /** 外置扩展包目录;为空表示不启用外置装载。 */
    private final String providerDir;
    private final IdentityProviderRegistry registry;

    public ExternalProviderLoader(@Value("${iam.provider.dir:}") String providerDir,
                                  IdentityProviderRegistry registry) {
        this.providerDir = providerDir;
        this.registry = registry;
    }

    /**
     * 启动时装载外置扩展包。
     * <p>目录未配置、不存在、扫描失败或包内无 jar 时只记日志并跳过,不阻断启动。</p>
     */
    @PostConstruct
    public void load() {
        if (providerDir == null || providerDir.isBlank()) {
            log.info("未配置外置扩展包目录,跳过外置身份提供方装载");
            return;
        }
        Path dir = Paths.get(providerDir.trim());
        if (!Files.isDirectory(dir)) {
            log.info("外置扩展包目录不存在,跳过装载: {}", dir);
            return;
        }
        List<Path> jars;
        try (Stream<Path> files = Files.list(dir)) {
            jars = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("扫描外置扩展包目录失败,已跳过: dir={}, error={}", dir, e.getMessage());
            return;
        }
        if (jars.isEmpty()) {
            log.info("外置扩展包目录内没有 jar: dir={}", dir);
            return;
        }
        for (Path jar : jars) {
            loadJar(jar);
        }
    }

    /**
     * 装载一个扩展包,把其中的工厂注册进提供方注册表。
     * <p>类加载器不关闭:工厂在注册后仍要持续被调用以创建提供方实例。</p>
     *
     * @param jar 扩展包文件路径
     */
    private void loadJar(Path jar) {
        try {
            URLClassLoader loader = new URLClassLoader("iam-provider-" + jar.getFileName(),
                    new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
            int count = 0;
            for (IdentityProviderFactory<?> factory : servicesOf(loader)) {
                registry.register(factory);
                count++;
            }
            log.info("已装载外置身份提供方扩展包: jar={}, 提供方数量={}", jar.getFileName(), count);
        } catch (Exception | ServiceConfigurationError | LinkageError e) {
            // 服务声明类不存在抛 ServiceConfigurationError,jar 与平台版本不匹配抛
            // LinkageError;两者均继承 Error,只捕获 Exception 拦不住,需一并处理
            log.error("外置身份提供方扩展包装载失败,已跳过: jar={}, error={}",
                    jar.getFileName(), e.getMessage());
        }
    }

    /**
     * 读取扩展包里的服务声明。
     * <p>服务接口带泛型参数,声明文件按原始类型加载后需一次受控转换;
     * 扩展包侧的约束仅为提供一个无参构造的工厂实现。</p>
     *
     * @param loader 扩展包类加载器
     * @return 工厂服务加载器
     */
    @SuppressWarnings("unchecked")
    private static ServiceLoader<IdentityProviderFactory<?>> servicesOf(ClassLoader loader) {
        return (ServiceLoader<IdentityProviderFactory<?>>) (ServiceLoader<?>)
                ServiceLoader.load(IdentityProviderFactory.class, loader);
    }
}
