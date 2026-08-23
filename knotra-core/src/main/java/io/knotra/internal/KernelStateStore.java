package io.knotra.internal;

import java.util.Objects;

/**
 * Sole owner of the volatile published kernel state.
 *
 * <p>Reads may run without the coordinator lock and receive one self-consistent generation.
 * Commits run only while the caller holds the coordinator lock and must present the exact
 * state from which the candidate was constructed. The store never schedules callbacks and
 * never replaces the coordinator's serialization responsibility.</p>
 */
final class KernelStateStore {
    private final Object coordinatorLock;
    private volatile PublishedKernelState current;

    private KernelStateStore(Object coordinatorLock, PublishedKernelState initial) {
        this.coordinatorLock = Objects.requireNonNull(coordinatorLock, "coordinatorLock");
        this.current = Objects.requireNonNull(initial, "initial");
    }

    static KernelStateStore initial(
            Object coordinatorLock,
            ContextHandleImpl root) {
        return new KernelStateStore(
                coordinatorLock,
                PublishedKernelState.initial(root));
    }

    PublishedKernelState read() {
        return current;
    }

    PublishedKernelState commitLocked(
            PublishedKernelState expected,
            PublishedKernelState next) {
        if (!Thread.holdsLock(coordinatorLock)) {
            throw new CommitRejectedException(
                    "kernel state commit requires coordinator lock");
        }
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        PublishedKernelState current = this.current;
        if (current != expected || current.generation != expected.generation) {
            throw new CommitRejectedException("stale kernel state expected");
        }
        if (next.generation <= current.generation) {
            throw new CommitRejectedException("kernel state generation must increase");
        }
        this.current = next;
        return next;
    }

    /** Stable internal rejection used by kernel commit failure convergence. */
    static final class CommitRejectedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        CommitRejectedException(String message) {
            super(message);
        }
    }
}
