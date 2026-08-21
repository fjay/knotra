package io.knotra.pf4j;

import java.nio.file.Path;

import org.pf4j.DefaultPluginManager;

/**
 * 使用共享合约加载策略的 PF4J 默认插件管理器，始终作为适配器私有实现保留。 */
final class SharedContractPluginManager extends DefaultPluginManager {
    SharedContractPluginManager(Path pluginsRoot, KnotraClassLoaderPolicy policy) {
        super(pluginsRoot);
        // super() 期间 PF4J 会初始化插件加载器，此时本对象字段尚未赋值，只能事后替换。
        this.pluginLoader = new SharedContractPluginLoader(this, policy);
    }
}
