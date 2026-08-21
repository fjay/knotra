package io.knotra.pf4j;

import java.nio.file.Path;

import org.pf4j.DefaultPluginManager;

final class SharedContractPluginManager extends DefaultPluginManager {

    SharedContractPluginManager(Path pluginsRoot, KnotraClassLoaderPolicy policy) {
        super(pluginsRoot);
        // AbstractPluginManager initializes during super(), before this object's fields are set.
        this.pluginLoader = new SharedContractPluginLoader(this, policy);
    }
}
