package io.knotra.pf4j;

import org.pf4j.ClassLoadingStrategy;
import org.pf4j.PluginClassLoader;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginManager;

final class SharedContractClassLoader extends PluginClassLoader {

    private final KnotraClassLoaderPolicy policy;

    SharedContractClassLoader(
            PluginManager pluginManager,
            PluginDescriptor pluginDescriptor,
            KnotraClassLoaderPolicy policy) {
        super(pluginManager, pluginDescriptor, policy.sharedParent(), ClassLoadingStrategy.APD);
        this.policy = policy;
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        if (policy.isShared(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            // A private copy of a shared API loses to the exact host identity.
            return policy.sharedParent().loadClass(name);
        }
        return super.loadClass(name);
    }
}
