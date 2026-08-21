package io.knotra.loader;

import java.util.Optional;

/**
 * 工厂引用的解析入口：把期望树中的 {@link FactoryRef} 解析为可执行的受控工厂。
 *
 * <p>解析器是 Loader 与实现来源（classpath、PF4J artifact 桥接等）之间唯一的
 * raw 配置边界。Loader 只消费不透明的 {@link ResolvedFactory}，不感知实现如何
 * 加载，也不会拿到工厂实例、artifact 管理器或 RuntimeTransaction。</p>
 */
@FunctionalInterface
public interface ComponentFactoryResolver {

    /**
     * 按引用解析组件工厂。
     *
     * <p>返回空 {@code Optional} 表示没有匹配实现；解析抛出的异常同样会被
     * Loader 记为 {@link LoaderDiagnosticCode#RESOLUTION_FAILED} 诊断。任何条目
     * 解析失败都会让整批期望树在结构变更前被拒绝。</p>
     */
    Optional<ResolvedFactory> resolve(FactoryRef ref);
}
