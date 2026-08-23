package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TransitionScheduler 的预约、合并、取消和驱动契约。 */
final class TransitionSchedulerTest {
    private static final CapabilityKey<String> STOP_KEY =
            CapabilityKey.of("scheduler-stop-key", String.class);

    private final KnotraRuntime publicRuntime = KnotraRuntime.create();
    private final DefaultKnotraRuntime runtime =
            (DefaultKnotraRuntime) publicRuntime;

    @AfterEach
    void tearDown() {
        publicRuntime.close();
    }

    @Test
    void creatorIsTheOnlyDriverForAMergedReservation() {
        ComponentRuntime component = ComponentRuntimeStateTest.component();
        RecordingExecutor executor = new RecordingExecutor();
        List<String> driven = new ArrayList<>();
        TransitionScheduler scheduler = new TransitionScheduler(
                new Object(), executor,
                (candidate, future) -> driven.add(candidate.handleId()),
                () -> 17L);
        ComponentRuntime.Reservation first =
                component.reserveTransition(17L, "first request");
        ComponentRuntime.Reservation second =
                component.reserveTransition(18L, "merged request");

        TransitionPlan plan = TransitionPlan.of(
                List.of(first, second),
                List.of(component.handleId()),
                List.of(component.handleId()));
        scheduler.drive(plan);
        scheduler.drive(plan);

        assertEquals(List.of(component.handleId()), plan.createdIds());
        assertEquals(List.of(component.handleId()), plan.orderedIds());
        assertEquals(1, executor.tasks.size(), "merged reservation must not be submitted twice");
        executor.tasks.getFirst().run();
        assertEquals(List.of(component.handleId()), driven);
    }

    @Test
    void stopOrderPutsDependentsBeforeProviders() {
        MountHandle provider = mount(
                "z-provider",
                ComponentDescriptor.named("z-provider"),
                context -> context.provide(STOP_KEY, "value"));
        MountHandle consumer = mount(
                "a-consumer",
                ComponentDescriptor.named(
                        "a-consumer", CapabilityRequirement.required(STOP_KEY)),
                context -> { });
        awaitActive(provider);
        awaitActive(consumer);

        PublishedKernelState state = runtime.publishedState();
        RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
        KernelStateDraft indexDraft = new KernelStateDraft(state);
        draft.components.put(provider.handleId(), draft.components
                .get(provider.handleId()).withState(ComponentState.STOPPING));
        draft.components.put(consumer.handleId(), draft.components
                .get(consumer.handleId()).withState(ComponentState.STOPPING));
        TransitionScheduler scheduler = new TransitionScheduler(
                runtime.coordinator,
                task -> { },
                (component, future) -> { },
                runtime::pendingTime);
        List<ComponentRuntime.Reservation> reservations = new ArrayList<>();
        TransitionPlan plan;
        synchronized (runtime.coordinator) {
            plan = scheduler.prepare(
                    state,
                    draft,
                    Set.of(provider.handleId(), consumer.handleId()),
                    new ExecutableCommitPlan(),
                    indexDraft,
                    reservations);
        }

        assertEquals(
                List.of(consumer.handleId(), provider.handleId()),
                plan.orderedIds());
        assertEquals(plan.reservations(), List.copyOf(reservations));
        scheduler.completeCancelled(scheduler.cancelCreated(plan));
    }

    @Test
    void partialReservationFailureCancelsCreatedReservationsOutsideCoordinator() {
        MountHandle first = waitingMount("partial-first");
        MountHandle second = waitingMount("partial-second");
        TransitionScheduler scheduler = new TransitionScheduler(
                runtime.coordinator,
                task -> { },
                (component, future) -> { },
                runtime::pendingTime);
        AtomicInteger calls = new AtomicInteger();
        scheduler.transitionReservationFaultProbe = index -> {
            calls.incrementAndGet();
            if (index == 1) {
                throw new IllegalStateException("injected reservation fault");
            }
        };

        PublishedKernelState state = runtime.publishedState();
        RuntimeView.Draft draft = new RuntimeView.Draft(state.view);
        KernelStateDraft indexDraft = new KernelStateDraft(state);
        List<ComponentRuntime.Reservation> reservations = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> {
            synchronized (runtime.coordinator) {
                scheduler.prepare(
                        state,
                        draft,
                        Set.of(first.handleId(), second.handleId()),
                        new ExecutableCommitPlan(),
                        indexDraft,
                        reservations);
            }
        });
        assertEquals(2, calls.get());
        assertEquals(1, reservations.size());
        assertTrue(reservations.getFirst().created());
        assertFalse(reservations.getFirst().future().isDone());

        List<CompletableFuture<ComponentState>> cancelled =
                scheduler.cancelCreated(reservations);
        scheduler.completeCancelled(cancelled);
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> reservations.getFirst().future().get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof TransitionCancelledStateException);
        assertEquals(null, reservations.getFirst().component().pendingSnapshot());
    }

    @Test
    void executorRejectionFailsReservationAndClearsPendingMetadata() throws Exception {
        ComponentRuntime component = ComponentRuntimeStateTest.component();
        TransitionScheduler scheduler = new TransitionScheduler(
                new Object(),
                task -> {
                    throw new RejectedExecutionException("executor closed");
                },
                (candidate, future) -> {
                    throw new AssertionError("driver must not run after rejection");
                },
                () -> 19L);
        ComponentRuntime.Reservation reservation =
                component.reserveTransition(19L, "rejected transition");
        assertNotNull(component.pendingSnapshot());

        scheduler.drive(TransitionPlan.of(
                List.of(reservation),
                List.of(component.handleId()),
                List.of(component.handleId())));

        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> reservation.future().get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof TransitionRejectedStateException);
        assertTrue(component.noLongerOwnsTransition(reservation.future()));
        assertEquals(null, component.pendingSnapshot());
    }

    @Test
    void successfulDriveClearsPendingMetadata() {
        ComponentRuntime component = ComponentRuntimeStateTest.component();
        RecordingExecutor executor = new RecordingExecutor();
        TransitionScheduler scheduler = new TransitionScheduler(
                new Object(),
                executor,
                (candidate, future) ->
                        candidate.finishTransition(future, ComponentState.ACTIVE).run(),
                () -> 20L);
        ComponentRuntime.Reservation reservation =
                component.reserveTransition(20L, "successful transition");
        assertNotNull(component.pendingSnapshot());

        scheduler.drive(TransitionPlan.of(
                List.of(reservation),
                List.of(component.handleId()),
                List.of(component.handleId())));
        executor.tasks.getFirst().run();

        assertTrue(reservation.future().isDone());
        assertEquals(null, component.pendingSnapshot());
    }

    @Test
    void publishFailureCancelsObserverMergedIntoCreatedReservation() throws Exception {
        MountHandle handle = mount(
                "publish-failure",
                ComponentDescriptor.named("publish-failure"),
                context -> { });
        awaitActive(handle);
        AtomicReference<CompletableFuture<ComponentState>> observer = new AtomicReference<>();
        runtime.transitionScheduler.transitionReservationProbe = () -> {
            observer.set(handle.whenSettled().toCompletableFuture());
            throw new IllegalStateException("publish failed after reservation");
        };

        assertThrows(RuntimeException.class, () ->
                publicRuntime.advanced().transact(transaction -> {
                    transaction.dispose(handle);
                    return null;
                }));

        CompletableFuture<ComponentState> pending = observer.get();
        assertNotNull(pending);
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> pending.get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof TransitionCancelledStateException);
        runtime.transitionScheduler.transitionReservationProbe = null;
    }

    @Test
    void removedComponentReservationCompletesAsDisposed() throws Exception {
        MountHandle handle = mount(
                "removed-component",
                ComponentDescriptor.named("removed-component"),
                context -> { });
        awaitActive(handle);
        ComponentRuntime component = runtime.publishedState()
                .index.components.get(handle.handleId());
        handle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);

        ComponentRuntime.Reservation reservation =
                component.reserveTransition(runtime.pendingTime(), "stale component");
        runtime.transitionScheduler.drive(TransitionPlan.of(
                List.of(reservation),
                List.of(component.handleId()),
                List.of(component.handleId())));

        assertEquals(ComponentState.DISPOSED,
                reservation.future().get(10, TimeUnit.SECONDS));
        assertTrue(component.noLongerOwnsTransition(reservation.future()));
    }

    private MountHandle mount(
            String mountId,
            ComponentDescriptor descriptor,
            MountFactory.Start start) {
        return publicRuntime.advanced().transact(transaction ->
                        transaction.mount(publicRuntime.root(), mountId,
                                MountFactory.of("scheduler-test", descriptor, start)))
                .value();
    }

    private MountHandle waitingMount(String mountId) {
        return mount(
                mountId,
                ComponentDescriptor.named(
                        mountId, CapabilityRequirement.required(STOP_KEY)),
                context -> { });
    }

    private void awaitActive(MountHandle handle) {
        try {
            assertEquals(ComponentState.ACTIVE, handle.whenSettled()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static final class RecordingExecutor implements java.util.concurrent.Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }
    }
}
