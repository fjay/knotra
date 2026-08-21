package com.example.integration.plugin;

/**
 * Plugin-private event type. It exists only inside the artifact; the host can only load
 * an independent copy through a separate class loader, never this exact class.
 */
public record PluginEvent(String message) {
}
