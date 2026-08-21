package io.knotra.pf4j;

import java.nio.file.Files;
import java.nio.file.Path;

import org.pf4j.PluginDescriptor;
import org.pf4j.PluginLoader;
import org.pf4j.PluginManager;
import org.pf4j.util.FileUtils;

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
