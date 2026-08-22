package io.knotra.spring;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.CompletionStage;

/**
 * 在 Activation 拥有的 Spring 子上下文物理关闭之前执行的、支持失败与重试的停机钩子。
 *
 * <p>Knotra 会优先调用该钩子。若其返回的 stage 正常完成，Knotra 始终会自行执行物理清理：
 * 对于活跃上下文调用 {@code context.close()}，或在 refresh 未完成时调用 {@code destroySingletons()}。
 * 若钩子抛出异常或返回异常完成的 stage，Knotra 不会关闭上下文；失败的生命周期条目保持可重试状态。
 * 因此实现必须具备幂等性，绝不能依赖后续成功的钩子调用来撤销部分工作。
 *
 * <p>该钩子专为失败必须对 Knotra 可见的资源而设计。默认的 Spring 关闭路径是不透明的：
 * Spring 通常会捕获并记录其内部 Bean 的销毁错误而非向上抛出，导致这些错误无法可靠参与 Knotra 清理重试。
 */
@FunctionalInterface
public interface SpringContextCloser {

    /**
     * 在物理上下文清理前执行自定义停机操作。
     *
     * @param context 当前 Activation 所拥有的子上下文
     * @return 当钩子操作收敛时正常完成的 stage，或异常完成以使上下文和此钩子保留再次清理重试资格的 stage
     */
    CompletionStage<Void> close(AnnotationConfigApplicationContext context) throws Exception;
}
