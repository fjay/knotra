package io.knotra.spring;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.CompletionStage;

/**
 * Failable and retryable shutdown hook run before an Activation-owned Spring child context is
 * physically closed.
 *
 * <p>Knotra invokes the hook first. If its returned stage completes normally, Knotra always
 * performs the physical cleanup itself: {@code context.close()} for an active context, or
 * {@code destroySingletons()} when refresh never completed. If the hook throws or returns an
 * exceptionally completed stage, Knotra does not close the context; the failed lifecycle entry
 * remains retryable through the component's retry operation. Implementations should therefore
 * be idempotent and must not rely on a later successful hook invocation to undo partial work.
 *
 * <p>The hook is intended for resources whose failures must be observable by Knotra. The default
 * Spring shutdown path is opaque cleanup: Spring commonly catches and logs destruction errors
 * for its internal beans instead of propagating them, so those errors cannot reliably participate
 * in Knotra cleanup retries.
 */
@FunctionalInterface
public interface SpringContextCloser {

    /**
     * Runs custom shutdown work before physical context cleanup.
     *
     * @param context the child context owned by the current Activation
     * @return a stage that completes normally when hook work has converged, or exceptionally to
     *         leave the context and this hook eligible for another cleanup attempt
     */
    CompletionStage<Void> close(AnnotationConfigApplicationContext context) throws Exception;
}
