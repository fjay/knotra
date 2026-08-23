package io.knotra.internal;

import io.knotra.ComponentState;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 单次结构提交的不可变过渡预约清单；生命周期止于对应 future 收敛。 */
final class TransitionPlan {
    static final TransitionPlan EMPTY =
            new TransitionPlan(List.of(), List.of(), List.of());

    private final List<ComponentRuntime.Reservation> reservations;
    private final List<String> createdIds;
    private final List<String> orderedIds;

    private TransitionPlan(
            List<ComponentRuntime.Reservation> reservations,
            List<String> createdIds,
            List<String> orderedIds) {
        this.reservations = List.copyOf(reservations);
        this.createdIds = List.copyOf(createdIds);
        this.orderedIds = List.copyOf(orderedIds);
    }

    static TransitionPlan of(
            List<ComponentRuntime.Reservation> reservations,
            List<String> createdIds,
            List<String> orderedIds) {
        if (reservations.isEmpty() && createdIds.isEmpty() && orderedIds.isEmpty()) {
            return EMPTY;
        }
        return new TransitionPlan(reservations, createdIds, orderedIds);
    }

    List<ComponentRuntime.Reservation> reservations() {
        return reservations;
    }

    List<String> createdIds() {
        return createdIds;
    }

    List<String> orderedIds() {
        return orderedIds;
    }

    List<CompletableFuture<ComponentState>> futures() {
        return reservations.stream()
                .map(ComponentRuntime.Reservation::future)
                .toList();
    }
}
