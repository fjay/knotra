package io.knotra.pf4j.spi;

import java.util.Collection;

import org.pf4j.ExtensionPoint;

/**
 * PF4J artifact 发布给宿主的共享提供方入口。
 *
 * <p>提供方只暴露带显式配置 token 的受控工厂；宿主不会通过此接口取得可执行的
 * artifact 组件实例。加载并启动插件也不会隐式挂载任何组件。</p>
 */
public interface RuntimeComponentProvider extends ExtensionPoint {

    Collection<ExportedComponentFactory<?>> factories();
}
