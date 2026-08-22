package io.knotra;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 组件结算后不是 ACTIVE，或有界等待未在指定时间内收敛。
 *
 * <p>异常携带稳定句柄标识与目标诊断；为避免保留插件 artifact，不携带组件实例，
 * 也不保留任何 Throwable、Class 或 ClassLoader 引用（包括 JDK 中断或超时异常）。
 * 等待被中断时调用线程的中断标记会被保留并恢复；超时通过 {@link #timeout()} 表达；
 * 中断与结算失败同时以稳定文本诊断编码。</p>
 *
 * <p>{@code requireActive} 只会在失败状态的快照不是 ACTIVE 时抛出本异常，
 * 因此 {@link #state()} 不会报告 ACTIVE。</p>
 */
public final class ComponentNotActiveException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ComponentState state;
    private final String handleId;
    private final String mountId;
    private final String componentId;
    private final String factoryId;
    private final String contextId;
    private final Duration timeout;
    private final List<RuntimeDiagnostic> diagnostics;

    public ComponentNotActiveException(
            ComponentState state,
            String handleId,
            String mountId,
            String componentId,
            String factoryId,
            String contextId,
            Duration timeout,
            List<RuntimeDiagnostic> diagnostics) {
        super(message(handleId, mountId, state, timeout, diagnostics));
        Objects.requireNonNull(diagnostics, "diagnostics");
        this.state = state;
        this.handleId = handleId;
        this.mountId = mountId;
        this.componentId = componentId;
        this.factoryId = factoryId;
        this.contextId = contextId;
        this.timeout = timeout;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public ComponentState state() {
        return state;
    }

    public String handleId() {
        return handleId;
    }

    public String mountId() {
        return mountId;
    }

    public String componentId() {
        return componentId;
    }

    public String factoryId() {
        return factoryId;
    }

    public String contextId() {
        return contextId;
    }

    /** null 表示无界等待；非 null 表示本次有界等待超时。 */
    public Duration timeout() {
        return timeout;
    }

    public List<RuntimeDiagnostic> diagnostics() {
        return diagnostics;
    }

    private static String message(
            String handleId,
            String mountId,
            ComponentState state,
            Duration timeout,
            List<RuntimeDiagnostic> diagnostics) {
        StringBuilder message = new StringBuilder("component is not ACTIVE: handle=")
                .append(handleId)
                .append(", mount=")
                .append(mountId)
                .append(", state=")
                .append(state);
        if (timeout != null) {
            message.append(", timeout=").append(timeout);
        }
        for (RuntimeDiagnostic diagnostic : diagnostics) {
            message.append("; ")
                    .append(diagnostic.code())
                    .append(" [")
                    .append(diagnostic.targetId())
                    .append("] ")
                    .append(diagnostic.message());
        }
        return message.toString();
    }
}
