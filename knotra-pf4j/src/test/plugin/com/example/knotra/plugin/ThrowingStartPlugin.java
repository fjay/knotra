package com.example.knotra.plugin;

import org.pf4j.Plugin;

public final class ThrowingStartPlugin extends Plugin {

    @Override
    public void start() {
        throw new IllegalStateException("intentional PF4J start failure");
    }
}
