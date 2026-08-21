package io.knotra.events;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class DefaultEventBus implements EventBus {
    private static final AtomicLong BUS_IDS = new AtomicLong();

    private final String busId = "event-bus-" + BUS_IDS.incrementAndGet();
    private final ClassLoader hostContextClassLoader;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<SubscriptionIndex, List<RegisteredSubscription>> subscriptions = new HashMap<>();
    private final Map<String, EventBinding> eventBindings = new HashMap<>();
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> acceptedDispatches =
            new ConcurrentLinkedQueue<>();
    private final AtomicLong sequences = new AtomicLong();
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private boolean closed;
    private CompletableFuture<Void> closeFuture;

    DefaultEventBus() {
        this.hostContextClassLoader = Thread.currentThread().getContextClassLoader();
        this.executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, busId + "-worker");
            thread.setDaemon(true);
            thread.setContextClassLoader(hostContextClassLoader);
            return thread;
        });
        this.ownsExecutor = true;
    }

    DefaultEventBus(ExecutorService executor, boolean ownsExecutor) {
        this.hostContextClassLoader = Thread.currentThread().getContextClassLoader();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
    }

    @Override
    public String busId() {
        return busId;
    }

    @Override
    public <T> EventSubscription on(
            EventDefinition<T> definition,
            EventListener<? super T> listener) {
        Objects.requireNonNull(listener, "listener");
        return register(definition, EventMode.SYNC, listener);
    }

    @Override
    public <T> EventSubscription onParallel(
            EventDefinition<T> definition,
            ParallelEventListener<? super T> listener) {
        Objects.requireNonNull(listener, "listener");
        return register(definition, EventMode.PARALLEL, listener);
    }

    @Override
    public <T> EventSubscription onSerial(
            EventDefinition<T> definition,
            SerialEventListener<? super T> listener) {
        Objects.requireNonNull(listener, "listener");
        return register(definition, EventMode.SERIAL, listener);
    }

    @Override
    public <T> EventSubscription onBail(
            EventDefinition<T> definition,
            BailEventListener<? super T> listener) {
        Objects.requireNonNull(listener, "listener");
        return register(definition, EventMode.BAIL, listener);
    }

    @Override
    public <T> EventSubscription onWaterfall(
            EventDefinition<T> definition,
            WaterfallEventListener<T> listener) {
        Objects.requireNonNull(listener, "listener");
        return register(definition, EventMode.WATERFALL, listener);
    }

    @Override
    public <T> EventDispatch<T> emit(EventDefinition<T> definition, T event) {
        Accepted<T> accepted = accept(definition, event, EventMode.SYNC);
        try {
            List<EventFailure> failures = new ArrayList<>();
            for (RegisteredSubscription subscription : accepted.listeners()) {
                try {
                    EventListener<? super T> listener = listener(subscription, EventMode.SYNC);
                    withListenerContext(subscription, () -> {
                        listener.listen(accepted.event());
                        return null;
                    });
                } catch (Throwable error) {
                    failures.add(failure(subscription, error));
                }
            }
            return EventDispatch.sync(accepted.event(), accepted.listeners().size(), failures);
        } finally {
            finish(accepted);
        }
    }

    @Override
    public <T> CompletionStage<EventDispatch<T>> parallel(
            EventDefinition<T> definition,
            T event) {
        Accepted<T> accepted = accept(definition, event, EventMode.PARALLEL);
        try {
            List<CompletableFuture<ListenerOutcome<T>>> futures = new ArrayList<>();
            for (RegisteredSubscription subscription : accepted.listeners()) {
                futures.add(parallelListener(subscription, accepted.event()));
            }
            CompletableFuture<Void> all = CompletableFuture.allOf(
                    futures.toArray(CompletableFuture[]::new));
            return all.thenApply(ignored -> {
                int completed = 0;
                List<EventFailure> failures = new ArrayList<>();
                for (CompletableFuture<ListenerOutcome<T>> future : futures) {
                    ListenerOutcome<T> outcome = future.join();
                    if (outcome.failure() == null) {
                        completed++;
                    } else {
                        failures.add(outcome.failure());
                    }
                }
                return new EventDispatch<>(accepted.event(), accepted.event(), EventMode.PARALLEL,
                        accepted.listeners().size(), completed, false, failures);
            }).whenComplete((ignored, error) -> finish(accepted));
        } catch (Throwable error) {
            finish(accepted);
            return failed(error);
        }
    }

    @Override
    public <T> CompletionStage<EventDispatch<T>> serial(
            EventDefinition<T> definition,
            T event) {
        return sequential(definition, event, EventMode.SERIAL);
    }

    @Override
    public <T> CompletionStage<EventDispatch<T>> bail(
            EventDefinition<T> definition,
            T event) {
        return sequential(definition, event, EventMode.BAIL);
    }

    @Override
    public <T> CompletionStage<EventDispatch<T>> waterfall(
            EventDefinition<T> definition,
            T event) {
        return sequential(definition, event, EventMode.WATERFALL);
    }

    private <T> CompletionStage<EventDispatch<T>> sequential(
            EventDefinition<T> definition,
            T event,
            EventMode mode) {
        Accepted<T> accepted = accept(definition, event, mode);
        try {
            DispatchState<T> state = DispatchState.initial(
                    accepted.event(), mode, accepted.listeners().size());
            for (RegisteredSubscription subscription : accepted.listeners()) {
                state = composeListener(state, subscription);
            }
            return state.future().thenApply(DispatchState::toDispatch)
                    .whenComplete((ignored, error) -> finish(accepted));
        } catch (Throwable error) {
            finish(accepted);
            return failed(error);
        }
    }

    @Override
    public EventBusSnapshot snapshot() {
        lock.readLock().lock();
        try {
            List<EventBusSnapshot.Item> items = new ArrayList<>();
            for (List<RegisteredSubscription> listeners : subscriptions.values()) {
                for (RegisteredSubscription subscription : listeners) {
                    if (subscription.active()) {
                        items.add(subscription.toSnapshotItem());
                    }
                }
            }
            items.sort(Comparator
                    .comparing(EventBusSnapshot.Item::eventName)
                    .thenComparing(item -> item.mode().ordinal())
                    .thenComparingLong(EventBusSnapshot.Item::sequence));
            return new EventBusSnapshot(busId, closed, items);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public CompletionStage<Void> whenIdle() {
        lock.writeLock().lock();
        try {
            List<CompletableFuture<Void>> pending = snapshotAcceptedDispatches();
            if (pending.isEmpty()) {
                pruneIdleEventBindings();
            }
            return whenDispatchesSettle(pending);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        List<CompletableFuture<Void>> pending;
        boolean owner = false;
        lock.writeLock().lock();
        try {
            if (closeFuture == null) {
                closed = true;
                closeFuture = new CompletableFuture<>();
                owner = true;
                pending = snapshotAcceptedDispatches();
                for (List<RegisteredSubscription> listeners : subscriptions.values()) {
                    for (RegisteredSubscription subscription : listeners) {
                        subscription.markClosed();
                    }
                }
                subscriptions.clear();
                eventBindings.clear();
            } else {
                pending = List.of();
            }
        } finally {
            lock.writeLock().unlock();
        }

        if (owner) {
            whenDispatchesSettle(pending).whenComplete((ignored, error) -> {
                if (error != null) {
                    closeFuture.completeExceptionally(error);
                } else {
                    stopOwnedExecutor();
                }
            });
        }
        return closeFuture;
    }

    @Override
    public void close() {
        closeAsync().toCompletableFuture().join();
    }

    private void stopOwnedExecutor() {
        if (!ownsExecutor) {
            closeFuture.complete(null);
            return;
        }

        executor.shutdownNow();
        Thread terminator = new Thread(() -> {
            try {
                if (!executor.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                    closeFuture.completeExceptionally(
                            new IllegalStateException("event bus executor did not terminate: " + busId));
                    return;
                }
                closeFuture.complete(null);
            } catch (Throwable error) {
                closeFuture.completeExceptionally(asException(error));
            }
        }, busId + "-close");
        terminator.setDaemon(true);
        terminator.setContextClassLoader(hostContextClassLoader);
        terminator.start();
    }

    private <T> DispatchState<T> composeListener(
            DispatchState<T> state,
            RegisteredSubscription subscription) {
        CompletableFuture<DispatchState<T>> current = state.future();
        switch (state.mode()) {
            case SERIAL -> {
                return DispatchState.of(state, current.thenCompose(next -> {
                    if (next.stopped()) {
                        return CompletableFuture.completedFuture(next);
                    }
                    return serialListener(subscription, next).thenApply(outcome ->
                            next.afterListener(outcome.continueDispatch(), outcome.failure()));
                }));
            }
            case BAIL -> {
                return DispatchState.of(state, current.thenCompose(next -> {
                    if (next.stopped()) {
                        return CompletableFuture.completedFuture(next);
                    }
                    return bailListener(subscription, next).thenApply(outcome ->
                            next.afterListener(!outcome.continueDispatch(), outcome.failure()));
                }));
            }
            case WATERFALL -> {
                return DispatchState.of(state, current.thenCompose(next -> {
                    if (next.stopped()) {
                        return CompletableFuture.completedFuture(next);
                    }
                    return waterfallListener(subscription, next).thenApply(outcome ->
                            next.afterTransform(outcome.event(), outcome.failure()));
                }));
            }
            default -> throw new IllegalArgumentException(
                    "unsupported sequential mode: " + state.mode());
        }
    }

    private EventSubscription register(
            EventDefinition<?> definition,
            EventMode mode,
            Object listener) {
        requireDefinition(definition, mode);
        Objects.requireNonNull(listener, "listener");

        lock.writeLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("event bus is closed: " + busId);
            }
            EventBinding binding = acquireEventBinding(definition);
            Class<?> eventType = binding.eventType();
            RegisteredSubscription subscription = new RegisteredSubscription(
                    "event-subscription-" + busId + "-" + sequences.incrementAndGet(),
                    definition,
                    listener);
            subscriptions.computeIfAbsent(new SubscriptionIndex(definition.name(), eventType, mode),
                    ignored -> new ArrayList<>()).add(subscription);
            binding.subscriptionRegistered();
            return subscription;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private <T> Accepted<T> accept(
            EventDefinition<T> definition,
            T event,
            EventMode mode) {
        requireDefinition(definition, mode);
        Objects.requireNonNull(event, "event");

        lock.writeLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("event bus is closed: " + busId);
            }
            EventBinding binding = acquireEventBinding(definition);
            try {
                binding.dispatchAccepted();
                Class<?> eventType = binding.eventType();
                List<RegisteredSubscription> matching = subscriptions
                        .getOrDefault(new SubscriptionIndex(definition.name(), eventType, mode), List.of())
                        .stream()
                        .toList();
                T typedEvent = definition.eventType().cast(event);
                CompletableFuture<Void> dispatch = new CompletableFuture<>();
                for (RegisteredSubscription subscription : matching) {
                    subscription.accept(dispatch);
                }
                acceptedDispatches.add(dispatch);
                return new Accepted<>(typedEvent, matching, dispatch, binding);
            } catch (Throwable error) {
                releaseAcceptedBinding(binding);
                throw error;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private EventBinding acquireEventBinding(EventDefinition<?> definition) {
        Class<?> candidate = definition.eventType();
        EventBinding binding = eventBindings.get(definition.name());
        if (binding == null) {
            binding = new EventBinding(candidate);
            eventBindings.put(definition.name(), binding);
        } else if (binding.eventType() != candidate) {
            throw new IllegalArgumentException("event name is already bound to a different Class: "
                    + definition.name() + " ["
                    + identity(binding.eventType()) + " != " + identity(candidate) + "]");
        }
        return binding;
    }

    private void releaseAcceptedBinding(EventBinding binding) {
        if (binding.dispatchFinished()) {
            eventBindings.values().removeIf(current -> current == binding && current.isIdle());
        }
    }

    private void pruneIdleEventBindings() {
        eventBindings.values().removeIf(EventBinding::isIdle);
    }

    private static String identity(Class<?> type) {
        return type.getName() + "@" + System.identityHashCode(type);
    }

    private void finish(Accepted<?> accepted) {
        lock.writeLock().lock();
        try {
            acceptedDispatches.remove(accepted.dispatch());
            releaseAcceptedBinding(accepted.binding());
        } finally {
            lock.writeLock().unlock();
        }
        for (RegisteredSubscription subscription : accepted.listeners()) {
            subscription.release(accepted.dispatch());
        }
        accepted.dispatch().complete(null);
    }

    private List<CompletableFuture<Void>> snapshotAcceptedDispatches() {
        List<CompletableFuture<Void>> result = new ArrayList<>();
        for (CompletableFuture<Void> dispatch : acceptedDispatches) {
            if (!dispatch.isDone()) {
                result.add(dispatch);
            }
        }
        return result;
    }

    private static CompletionStage<Void> whenDispatchesSettle(
            List<CompletableFuture<Void>> dispatches) {
        return CompletableFuture.allOf(dispatches.toArray(CompletableFuture[]::new));
    }

    private void requireDefinition(EventDefinition<?> definition, EventMode mode) {
        Objects.requireNonNull(definition, "definition");
        if (definition.mode() != mode) {
            throw new IllegalArgumentException("event definition requires " + definition.mode()
                    + " dispatch, not " + mode);
        }
    }

    private EventFailure failure(RegisteredSubscription subscription, Throwable error) {
        return new EventFailure(
                subscription.subscriptionId(),
                subscription.eventName(),
                subscription.eventTypeName(),
                subscription.mode(),
                EventFailureText.describe(error));
    }

    @SuppressWarnings("unchecked")
    private <T> EventListener<? super T> listener(
            RegisteredSubscription subscription,
            EventMode mode) {
        verifyMode(subscription, mode);
        return (EventListener<? super T>) subscription.listener();
    }

    private <T> CompletableFuture<ListenerOutcome<T>> parallelListener(
            RegisteredSubscription subscription,
            T event) {
        try {
            CompletableFuture<CompletionStage<ListenerOutcome<T>>> submitted =
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            ParallelEventListener<? super T> listener =
                                    parallelListenerTyped(subscription);
                            CompletionStage<Void> stage = withListenerContext(
                                    subscription, (ContextCallback<CompletionStage<Void>>) () ->
                                            listener.listen(event));
                            Objects.requireNonNull(stage, "listener returned a null completion stage");
                            return stage.thenApply(ignored -> ListenerOutcome.<T>success());
                        } catch (Throwable error) {
                            return CompletableFuture.completedFuture(
                                    ListenerOutcome.<T>failure(failure(subscription, error)));
                        }
                    }, executor);
            return submitted.thenCompose(outcome -> outcome)
                    .exceptionally(error -> ListenerOutcome.failure(
                            failure(subscription, asException(error))));
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(
                    ListenerOutcome.failure(failure(subscription, asException(error))));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ParallelEventListener<? super T> parallelListenerTyped(
            RegisteredSubscription subscription) {
        verifyMode(subscription, EventMode.PARALLEL);
        return (ParallelEventListener<? super T>) subscription.listener();
    }

    private <T> CompletionStage<ListenerOutcome<T>> serialListener(
            RegisteredSubscription subscription,
            DispatchState<T> state) {
        try {
            SerialEventListener<? super T> listener = serialListenerTyped(subscription);
            CompletionStage<Boolean> stage = withListenerContext(
                    subscription, (ContextCallback<CompletionStage<Boolean>>) () ->
                            listener.listen(state.event()));
            Objects.requireNonNull(stage, "listener returned a null completion stage");
            CompletionStage<ListenerOutcome<T>> outcome = stage.thenApply(continueDispatch ->
                    new ListenerOutcome<>(continueDispatch, null, null));
            return outcome.exceptionally(error -> ListenerOutcome.<T>failure(
                    failure(subscription, asException(error))));
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(ListenerOutcome.failure(
                    failure(subscription, error)));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> SerialEventListener<? super T> serialListenerTyped(
            RegisteredSubscription subscription) {
        verifyMode(subscription, EventMode.SERIAL);
        return (SerialEventListener<? super T>) subscription.listener();
    }

    private <T> CompletionStage<ListenerOutcome<T>> bailListener(
            RegisteredSubscription subscription,
            DispatchState<T> state) {
        try {
            BailEventListener<? super T> listener = bailListenerTyped(subscription);
            boolean claimed = withListenerContext(
                    subscription, () -> listener.bail(state.event()));
            return CompletableFuture.completedFuture(new ListenerOutcome<>(claimed, null, null));
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(ListenerOutcome.failure(
                    failure(subscription, error)));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> BailEventListener<? super T> bailListenerTyped(
            RegisteredSubscription subscription) {
        verifyMode(subscription, EventMode.BAIL);
        return (BailEventListener<? super T>) subscription.listener();
    }

    private <T> CompletionStage<ListenerOutcome<T>> waterfallListener(
            RegisteredSubscription subscription,
            DispatchState<T> state) {
        try {
            WaterfallEventListener<T> listener = waterfallListenerTyped(subscription);
            CompletionStage<T> stage = withListenerContext(
                    subscription, (ContextCallback<CompletionStage<T>>) () ->
                            listener.transform(state.event()));
            Objects.requireNonNull(stage, "listener returned a null completion stage");
            CompletionStage<ListenerOutcome<T>> outcome = stage.thenApply(value ->
                    new ListenerOutcome<>(true, value, null));
            return outcome.exceptionally(error -> ListenerOutcome.<T>failure(
                    failure(subscription, asException(error))));
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(ListenerOutcome.failure(
                    failure(subscription, error)));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> WaterfallEventListener<T> waterfallListenerTyped(
            RegisteredSubscription subscription) {
        verifyMode(subscription, EventMode.WATERFALL);
        return (WaterfallEventListener<T>) subscription.listener();
    }

    private <R> R withListenerContext(
            RegisteredSubscription subscription,
            ContextCallback<R> callback) throws Throwable {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(subscription.listenerClassLoader());
        try {
            return callback.run();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private void verifyMode(RegisteredSubscription subscription, EventMode mode) {
        if (subscription.mode() != mode) {
            throw new IllegalArgumentException("subscription is not registered for " + mode);
        }
    }

    private static Exception asException(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof Exception exception ? exception : new IllegalStateException(cause);
    }

    private static <T> CompletionStage<EventDispatch<T>> failed(Throwable error) {
        CompletableFuture<EventDispatch<T>> future = new CompletableFuture<>();
        future.completeExceptionally(asException(error));
        return future;
    }

    private interface ContextCallback<R> {
        R run() throws Throwable;
    }

    private record SubscriptionIndex(String name, Class<?> eventType, EventMode mode) {
    }

    private record Accepted<T>(
            T event,
            List<RegisteredSubscription> listeners,
            CompletableFuture<Void> dispatch,
            EventBinding binding) {

        Accepted {
            listeners = List.copyOf(listeners);
        }
    }

    private record ListenerOutcome<T>(
            boolean continueDispatch,
            T event,
            EventFailure failure) {

        static <T> ListenerOutcome<T> success() {
            return new ListenerOutcome<>(true, null, null);
        }

        static <T> ListenerOutcome<T> failure(EventFailure failure) {
            return new ListenerOutcome<>(false, null, failure);
        }
    }

    private static final class DispatchState<T> {
        private final T initialEvent;
        private final T event;
        private final EventMode mode;
        private final int listenerCount;
        private final int completed;
        private final boolean stopped;
        private final List<EventFailure> failures;
        private final CompletableFuture<DispatchState<T>> future;

        private DispatchState(
                T initialEvent,
                T event,
                EventMode mode,
                int listenerCount,
                int completed,
                boolean stopped,
                List<EventFailure> failures,
                CompletableFuture<DispatchState<T>> future) {
            this.initialEvent = initialEvent;
            this.event = event;
            this.mode = mode;
            this.listenerCount = listenerCount;
            this.completed = completed;
            this.stopped = stopped;
            this.failures = List.copyOf(failures);
            this.future = future;
        }

        private static <T> DispatchState<T> initial(T event, EventMode mode, int listenerCount) {
            CompletableFuture<DispatchState<T>> future = new CompletableFuture<>();
            DispatchState<T> state = new DispatchState<>(
                    event, event, mode, listenerCount, 0, false, List.of(), future);
            future.complete(state);
            return state;
        }

        private static <T> DispatchState<T> of(
                DispatchState<T> previous,
                CompletableFuture<DispatchState<T>> future) {
            return new DispatchState<>(
                    previous.initialEvent,
                    previous.event,
                    previous.mode,
                    previous.listenerCount,
                    previous.completed,
                    previous.stopped,
                    previous.failures,
                    future);
        }

        private T initialEvent() {
            return initialEvent;
        }

        private T event() {
            return event;
        }

        private EventMode mode() {
            return mode;
        }

        private int listenerCount() {
            return listenerCount;
        }

        private int completed() {
            return completed;
        }

        private boolean stopped() {
            return stopped;
        }

        private List<EventFailure> failures() {
            return failures;
        }

        private CompletableFuture<DispatchState<T>> future() {
            return future;
        }

        private DispatchState<T> afterListener(boolean continueDispatch, EventFailure failure) {
            boolean failed = failure != null;
            return new DispatchState<>(
                    initialEvent,
                    event,
                    mode,
                    listenerCount,
                    failed ? completed : completed + 1,
                    failed || !continueDispatch,
                    failed ? append(failure) : failures,
                    CompletableFuture.completedFuture(null));
        }

        private DispatchState<T> afterTransform(T nextEvent, EventFailure failure) {
            boolean failed = failure != null;
            return new DispatchState<>(
                    initialEvent,
                    failed ? event : nextEvent,
                    mode,
                    listenerCount,
                    failed ? completed : completed + 1,
                    failed,
                    failed ? append(failure) : failures,
                    CompletableFuture.completedFuture(null));
        }

        private List<EventFailure> append(EventFailure failure) {
            List<EventFailure> next = new ArrayList<>(failures);
            next.add(failure);
            return next;
        }

        private EventDispatch<T> toDispatch() {
            return new EventDispatch<>(
                    initialEvent,
                    event,
                    mode,
                    listenerCount,
                    completed,
                    stopped,
                    failures);
        }
    }

    private static final class EventBinding {
        private final Class<?> eventType;
        private int subscriptions;
        private int dispatches;

        private EventBinding(Class<?> eventType) {
            this.eventType = eventType;
        }

        private Class<?> eventType() {
            return eventType;
        }

        private void subscriptionRegistered() {
            subscriptions++;
        }

        private void subscriptionRemoved() {
            subscriptions--;
        }

        private void dispatchAccepted() {
            dispatches++;
        }

        private boolean dispatchFinished() {
            return --dispatches == 0;
        }

        private boolean isIdle() {
            return subscriptions == 0 && dispatches == 0;
        }
    }

    private final class RegisteredSubscription implements EventSubscription {
        private final String subscriptionId;
        private final EventDefinition<?> definition;
        private final Object listener;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final ConcurrentLinkedQueue<CompletableFuture<Void>> acceptedDispatches =
                new ConcurrentLinkedQueue<>();
        private CompletableFuture<Void> closeFuture;

        private RegisteredSubscription(
                String subscriptionId,
                EventDefinition<?> definition,
                Object listener) {
            this.subscriptionId = subscriptionId;
            this.definition = definition;
            this.listener = listener;
        }

        @Override
        public String subscriptionId() {
            return subscriptionId;
        }

        @Override
        public String eventName() {
            return definition.name();
        }

        @Override
        public EventMode mode() {
            return definition.mode();
        }

        @Override
        public long sequence() {
            return Long.parseLong(subscriptionId.substring(subscriptionId.lastIndexOf('-') + 1));
        }

        @Override
        public boolean active() {
            return active.get();
        }

        @Override
        public void unsubscribe() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            lock.writeLock().lock();
            try {
                SubscriptionIndex index = new SubscriptionIndex(
                        eventName(), eventType(), mode());
                List<RegisteredSubscription> listeners = subscriptions.get(index);
                if (listeners != null && listeners.remove(this)) {
                    EventBinding binding = eventBindings.get(eventName());
                    if (binding != null) {
                        binding.subscriptionRemoved();
                        if (binding.isIdle()) {
                            eventBindings.remove(eventName(), binding);
                        }
                    }
                    if (listeners.isEmpty()) {
                        subscriptions.remove(index);
                    }
                }
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public synchronized CompletionStage<Void> closeAsync() {
            unsubscribe();
            if (closeFuture == null) {
                List<CompletableFuture<Void>> pending = new ArrayList<>();
                for (CompletableFuture<Void> dispatch : acceptedDispatches) {
                    if (!dispatch.isDone()) {
                        pending.add(dispatch);
                    }
                }
                closeFuture = CompletableFuture.allOf(
                        pending.toArray(CompletableFuture[]::new));
            }
            return closeFuture;
        }

        @Override
        public synchronized void close() {
            closeAsync().toCompletableFuture().join();
        }

        private void accept(CompletableFuture<Void> dispatch) {
            acceptedDispatches.add(dispatch);
        }

        private void release(CompletableFuture<Void> dispatch) {
            acceptedDispatches.remove(dispatch);
        }

        private Object listener() {
            return listener;
        }

        private ClassLoader listenerClassLoader() {
            return listener.getClass().getClassLoader();
        }

        private Class<?> eventType() {
            return definition.eventType();
        }

        private String eventTypeName() {
            return definition.eventType().getName();
        }

        private void markClosed() {
            active.set(false);
        }

        private EventBusSnapshot.Item toSnapshotItem() {
            return new EventBusSnapshot.Item(
                    subscriptionId,
                    definition.name(),
                    eventTypeName(),
                    definition.mode(),
                    sequence());
        }
    }
}
