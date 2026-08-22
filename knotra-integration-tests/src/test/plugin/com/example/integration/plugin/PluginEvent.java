package com.example.integration.plugin;

/**
 * 插件私有事件类型。它仅存在于 artifact 内部；宿主只能通过独立类加载器加载副本，绝无法直接加载此类。
 */
public record PluginEvent(String message) {
}
