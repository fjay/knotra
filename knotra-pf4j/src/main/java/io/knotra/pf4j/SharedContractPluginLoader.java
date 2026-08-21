package io.knotra.pf4j;

import java.nio.file.Files;
import java.nio.file.Path;

import org.pf4j.PluginDescriptor;
import org.pf4j.PluginLoader;
import org.pf4j.PluginManager;
import org.pf4j.util.FileUtils;

/**
 * 为每个 jar 插件创建共享合约 ClassLoader 的 PF4J 加载器。
 *
 * <p>加载器本身不做目录解析或启动决策，只确保 PF4J 创建的每个插件 ClassLoader
 * 都携带同一份宿主共享合约策略。</p>
 */
final class SharedContractPluginLoader implements PluginLoader {

    private final PluginManager pluginManager;
    private final KnotraClassLoaderPolicy policy;

    SharedContractPluginLoader(PluginManager pluginManager, KnotraClassLoaderPolicy policy) {
        this.pluginManager = pluginManager;
        this.policy = policy;
    }

    @Override
    public boolean isApplicable(Path pluginPath) {
        return Files.exists(pluginPath) && FileUtils.isJarFile(pluginPath);
    }

    @Override
    public ClassLoader loadPlugin(Path pluginPath, PluginDescriptor pluginDescriptor) {
        SharedContractClassLoader classLoader = new SharedContractClassLoader(
                pluginManager,
                pluginDescriptor,
                policy);
        classLoader.addFile(pluginPath.toFile());
        return classLoader;
    }
}
