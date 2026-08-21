package io.knotra.pf4j;

import org.pf4j.ClassLoadingStrategy;
import org.pf4j.PluginClassLoader;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginManager;

/**
 * 强制共享合约包使用宿主 ClassLoader 的 PF4J 插件 ClassLoader。
 *
 * <p>共享包命中时委派宿主加载，插件内私有副本会被忽略；非共享类沿用 PF4J 的
 * APD 策略，因此插件实现仍留在各自边界内。</p>
 */
final class SharedContractClassLoader extends PluginClassLoader {

    private final KnotraClassLoaderPolicy policy;

    SharedContractClassLoader(
            PluginManager pluginManager,
            PluginDescriptor pluginDescriptor,
            KnotraClassLoaderPolicy policy) {
        super(pluginManager, pluginDescriptor, policy.sharedParent(), ClassLoadingStrategy.APD);
        this.policy = policy;
    }

    /** 共享合约类强制走宿主委派，其余类交给 PF4J 插件加载策略。 */
    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        // 共享合约优先于插件 jar 与 PF4J 依赖委派，保证跨 artifact 的 Class 身份唯一。
        if (policy.isShared(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            // 已缓存类也必须是此前由宿主解析的同一身份，插件私有副本不能胜出。
            return policy.sharedParent().loadClass(name);
        }
        // 非共享实现保留 APD 顺序，让插件及其依赖仍按 PF4J 边界解析。
        return super.loadClass(name);
    }
}
