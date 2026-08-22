package io.knotra.internal;

import io.knotra.AsyncDynamicOperation;
import io.knotra.CapabilityKey;
import io.knotra.DynamicCapability;
import io.knotra.DynamicOperation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Activation 拥有的 DynamicCapability 实现；所有 provider 调用都发生在协调器锁外。 */
final class DynamicCapabilityImpl<T> implements DynamicCapability<T> {

    private final DefaultKnotraRuntime runtime;
    private final ActivationRuntime activation;
    private final CapabilityKey<T> key;

    DynamicCapabilityImpl(
            DefaultKnotraRuntime runtime,
            ActivationRuntime activation,
            CapabilityKey<T> key) {
        this.runtime = runtime;
        this.activation = activation;
        this.key = key;
    }

    @Override
    public boolean available() {
        return runtime.isDynamicAvailable(activation, key);
    }

    @Override
    public <R> R call(DynamicOperation<? super T, ? extends R> operation) {
        Objects.requireNonNull(operation, "operation");
        try (DefaultKnotraRuntime.DynamicLease<T> lease =
                runtime.acquireDynamic(activation, key)) {
            return operation.execute(lease.provider());
        } catch (InvocationTargetException error) {
            throw runtime.uncheckedInvocation(error);
        } catch (Exception error) {
            throw runtime.uncheckedDynamicCallback(error);
        }
    }

    @Override
    public <R> CompletionStage<R> callAsync(
            AsyncDynamicOperation<? super T, ? extends R> operation) {
        Objects.requireNonNull(operation, "operation");
        DefaultKnotraRuntime.DynamicLease<T> lease;
        try {
            lease = runtime.acquireDynamic(activation, key);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
        CompletionStage<? extends R> stage;
        try {
            stage = operation.execute(lease.provider());
        } catch (InvocationTargetException error) {
            lease.close();
            return CompletableFuture.failedFuture(runtime.uncheckedInvocation(error));
        } catch (Throwable error) {
            lease.close();
            return CompletableFuture.failedFuture(error);
        }
        if (stage == null) {
            lease.close();
            return CompletableFuture.completedFuture(null);
        }
        return attachAsyncLease(stage, lease);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <P extends T> P proxy(Class<P> interfaceType) {
        Objects.requireNonNull(interfaceType, "interfaceType");
        if (!interfaceType.isInterface()) {
            throw new IllegalArgumentException("dynamic proxy type must be an interface");
        }
        if (!interfaceType.equals(key.type())) {
            throw new IllegalArgumentException(
                    "dynamic proxy type must be exactly " + key.typeName());
        }
        return (P) Proxy.newProxyInstance(
                interfaceType.getClassLoader(),
                new Class<?>[] {interfaceType},
                (proxy, method, args) -> invokeProxy(proxy, method, args));
    }

    @Override
    public T proxy() {
        return proxy(key.type());
    }

    private Object invokeProxy(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> proxy.getClass().getInterfaces()[0].getName()
                        + "@" + Integer.toHexString(System.identityHashCode(proxy));
                default -> throw new IllegalStateException("unsupported object method");
            };
        }

        DefaultKnotraRuntime.DynamicLease<T> lease =
                runtime.acquireDynamic(activation, key);
        Object result;
        try {
            method.setAccessible(true);
            result = method.invoke(lease.provider(), args);
        } catch (InvocationTargetException error) {
            lease.close();
            Throwable cause = error.getCause();
            if (cause == null) {
                throw error;
            }
            throw cause;
        } catch (Throwable error) {
            lease.close();
            throw error;
        }

        if (result instanceof CompletionStage<?> stage) {
            // CompletionStage 必须等 stage 完成后释放；返回值也延后到释放之后完成。
            return attachAsyncLease(stage, lease);
        }
        lease.close();
        return result;
    }

    private <R> CompletionStage<R> attachAsyncLease(
            CompletionStage<? extends R> stage,
            DefaultKnotraRuntime.DynamicLease<T> lease) {
        CompletableFuture<R> result = new CompletableFuture<>();
        AtomicBoolean released = new AtomicBoolean();
        try {
            stage.whenComplete((value, error) -> {
                if (released.compareAndSet(false, true)) {
                    lease.close();
                }
                if (error != null) {
                    result.completeExceptionally(error);
                } else {
                    result.complete(value);
                }
            });
        } catch (Throwable compositionError) {
            if (released.compareAndSet(false, true)) {
                lease.close();
            }
            return CompletableFuture.failedFuture(compositionError);
        }
        return result;
    }
}
