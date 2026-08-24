package com.example.knotra.contract;

import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class ReferenceVault {

    private static final Set<WeakReference<ClassLoader>> loaders = ConcurrentHashMap.newKeySet();
    private static final AtomicReference<WeakReference<ClassLoader>> latestLoader =
            new AtomicReference<>();

    private ReferenceVault() {
    }

    public static WeakReference<ClassLoader> remember(ClassLoader loader) {
        WeakReference<ClassLoader> reference = new WeakReference<>(loader);
        loaders.add(reference);
        latestLoader.set(reference);
        return reference;
    }

    public static WeakReference<ClassLoader> latest() {
        return latestLoader.get();
    }

    public static void clear() {
        loaders.clear();
        latestLoader.set(null);
    }

    public static long liveLoaders() {
        loaders.removeIf(reference -> reference.get() == null);
        return loaders.stream().map(WeakReference::get).filter(loader -> loader != null).count();
    }
}
