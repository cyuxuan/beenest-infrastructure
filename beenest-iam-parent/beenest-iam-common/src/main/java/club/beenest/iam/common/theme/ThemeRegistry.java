package club.beenest.iam.common.theme;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 可用主题注册表。
 * <p>扫描 {@code classpath:/themes/} 下的主题目录,启动时加载一次。
 * 域主题配置前需经本表校验,不存在时回落全局主题。</p>
 */
@Component
public class ThemeRegistry {

    private static final String THEMES_LOCATION = "classpath:/themes/*/";

    private final Set<String> themes;

    public ThemeRegistry() {
        Set<String> found = new LinkedHashSet<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (Resource resource : resolver.getResources(THEMES_LOCATION)) {
                String name = resource.getFilename();
                if (name != null && !name.isBlank()) {
                    found.add(name);
                }
            }
        } catch (IOException ignored) {
            // 扫描失败(极端 classpath 异常)时按"无可用主题"处理:调用方回落全局主题,不阻断启动
        }
        this.themes = Collections.unmodifiableSet(found);
    }

    /**
     * 判断主题目录是否存在。
     *
     * @param name 主题名
     * @return true 表示存在
     */
    public boolean exists(String name) {
        return name != null && themes.contains(name);
    }

    /**
     * 全部可用主题名,顺序稳定。
     *
     * @return 主题名列表
     */
    public List<String> list() {
        return List.copyOf(themes);
    }
}
