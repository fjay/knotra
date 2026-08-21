package com.example.knotra.contract;

import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ReferenceVault {

    private static final Set<WeakReference<ClassLoader>> loaders = ConcurrentHashMap.newKeySet();

    private ReferenceVault() {
    }

    public static void remember(ClassLoader loader) {
        loaders.add(new WeakReference<>(loader));
    }

    public static void clear() {
        loaders.clear();
    }

    public static long liveLoaders() {
        loaders.removeIf(reference -> reference.get() == null);
        return loaders.stream().map(WeakReference::get).filter(loader -> loader != null).count();
    }
}
