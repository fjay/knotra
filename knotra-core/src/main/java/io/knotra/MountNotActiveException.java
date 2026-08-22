package io.knotra;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** A mount did not become ACTIVE, or a bounded activation wait did not converge. */
public final class MountNotActiveException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ComponentState state;
    private final String handleId;
    private final String mountId;
    private final String componentId;
    private final String factoryId;
    private final String contextId;
    private final Duration timeout;
    private final List<RuntimeDiagnostic> diagnostics;

    public MountNotActiveException(
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

    /** Null means unbounded; non-null means this bounded wait timed out. */
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
        StringBuilder message = new StringBuilder("mount is not ACTIVE: handle=")
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
