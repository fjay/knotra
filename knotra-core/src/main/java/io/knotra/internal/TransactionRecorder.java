package io.knotra.internal;

import io.knotra.CapabilityKey;
import io.knotra.ComponentFactory;
import io.knotra.ConfiguredMountHandle;
import io.knotra.ContextHandle;
import io.knotra.MountHandle;
import io.knotra.MountOptions;
import io.knotra.NoConfig;
import io.knotra.RegistrationHandle;
import io.knotra.RuntimeTransaction;
import io.knotra.StagedRegistration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 宿主事务回调的记录器：只积累 Intent，不直接修改视图。 */
final class TransactionRecorder implements RuntimeTransaction {
    private final DefaultKnotraRuntime runtime;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<Intent> intents = new ArrayList<>();
    private final Map<String, ProvisionalConfig> provisionalConfigs = new HashMap<>();

    TransactionRecorder(DefaultKnotraRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** 宿主回调结束后关闭；关闭后的任何记录动作都是编程错误。 */
    void close() {
        closed.set(true);
    }

    /** 导出不可变意图列表；提交路径只读取该快照。 */
    List<Intent> intents() {
        return List.copyOf(intents);
    }

    @Override
    public <T> StagedRegistration<T> provide(
            ContextHandle context,
            CapabilityKey<T> key,
            T value) {
        ensureOpen();
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        ContextHandleImpl target = requireThisContext(context);
        RegistrationHandleImpl handle = new RegistrationHandleImpl(
                runtime,
                Sequences.registration());
        record(new ProvideIntent(handle, target, key, value));
        return new StagedRegistrationImpl<>(handle, key, target);
    }

    @Override
    public void revoke(RegistrationHandle registration) {
        ensureOpen();
        Objects.requireNonNull(registration, "registration");
        RegistrationHandleImpl handle;
        if (registration instanceof RegistrationImpl<?> typed) {
            typed.requireFresh("revoke");
            handle = typed.registration();
        } else if (registration instanceof StagedRegistrationImpl<?> staged) {
            handle = staged.registration();
        } else if (registration instanceof RegistrationHandleImpl internal) {
            handle = internal;
        } else {
            handle = runtime.publishedState().index.registrationHandles
                    .get(registration.registrationId());
        }

        if (handle == null || handle.runtime != runtime) {
            throw new IllegalArgumentException(
                    "registration handle does not belong to this runtime");
        }
        record(new RevokeIntent(handle));
    }

    @Override
    public ContextHandle childContext(ContextHandle parent, String name) {
        ensureOpen();
        Objects.requireNonNull(parent, "parent");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("context name must not be blank");
        }
        ContextHandleImpl handle = new ContextHandleImpl(
                runtime,
                Sequences.context(name));
        record(new ChildContextIntent(
                requireThisContext(parent),
                name,
                handle));
        return handle;
    }

    @Override
    public <C> ConfiguredMountHandle<C> mount(
            ContextHandle context,
            String mountId,
            ComponentFactory<C> factory,
            C config) {
        return mount(context, mountId, factory, config, MountOptions.DEFAULT);
    }

    @Override
    public <C> ConfiguredMountHandle<C> mount(
            ContextHandle context,
            String mountId,
            ComponentFactory<C> factory,
            C config,
            MountOptions options) {
        ensureOpen();
        Objects.requireNonNull(context, "context");
        ContextHandleImpl target = requireThisContext(context);
        PreparedComponent<C> prepared = PreparedComponent.prepare(
                factory,
                config,
                options == null ? MountOptions.DEFAULT : options);
        ConfiguredMountHandleImpl<C> handle = new ConfiguredMountHandleImpl<>(
                runtime,
                Sequences.handle(),
                new MountHandleImpl.Identity(
                        mountId,
                        prepared.descriptor().componentId(),
                        prepared.factoryId(),
                        target.contextId()));
        provisionalConfigs.put(
                handle.handleId(),
                new ProvisionalConfig(prepared.config(), 1));
        record(new MountIntent(target, mountId, prepared, handle));
        return handle;
    }

    @Override
    public MountHandle mount(
            ContextHandle context,
            String mountId,
            ComponentFactory<NoConfig> factory) {
        return mountPlain(context, mountId, factory, MountOptions.DEFAULT);
    }

    @Override
    public MountHandle mount(
            ContextHandle context,
            String mountId,
            ComponentFactory<NoConfig> factory,
            MountOptions options) {
        return mountPlain(context, mountId, factory, options);
    }

    private MountHandleImpl mountPlain(
            ContextHandle context,
            String mountId,
            ComponentFactory<NoConfig> factory,
            MountOptions options) {
        ensureOpen();
        ContextHandleImpl target = requireThisContext(context);
        if (mountId == null || mountId.isBlank()) {
            throw new IllegalArgumentException("mountId must not be blank");
        }
        PreparedComponent<NoConfig> prepared = PreparedComponent.prepare(
                factory,
                NoConfig.INSTANCE,
                options == null ? MountOptions.DEFAULT : options);
        PlainMountHandleImpl handle = new PlainMountHandleImpl(
                runtime,
                Sequences.handle(),
                new MountHandleImpl.Identity(
                        mountId,
                        prepared.descriptor().componentId(),
                        prepared.factoryId(),
                        target.contextId()));
        record(new MountIntent(target, mountId, prepared, handle));
        return handle;
    }

    @Override
    public <C> ConfiguredMountHandle<C> reconfigure(
            ConfiguredMountHandle<C> handle,
            C config) {
        ensureOpen();
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(
                config,
                "config (use NoConfig.INSTANCE for components without configuration)");
        if (!(handle instanceof ConfiguredMountHandleImpl<C> typed)
                || typed.runtime != runtime
                || !ownsOrProvisionallyOwns(typed)) {
            throw new IllegalArgumentException(
                    "component handle does not belong to this runtime");
        }
        PreparedComponent<?> prepared = preparedFor(typed);
        ProvisionalConfig current = provisionalConfigFor(typed, prepared);
        Object normalized = normalizeFor(prepared, config);
        long expectedRevision = current.revision();
        boolean equivalent = Objects.equals(normalized, current.config());
        if (!equivalent) {
            provisionalConfigs.put(
                    typed.handleId(),
                    new ProvisionalConfig(normalized, expectedRevision + 1));
        }
        record(new ReconfigureIntent<>(
                typed,
                normalized,
                expectedRevision,
                equivalent));
        return typed;
    }

    @Override
    public void dispose(MountHandle handle) {
        ensureOpen();
        Objects.requireNonNull(handle, "handle");
        if (!(handle instanceof MountHandleImpl typed)
                || typed.runtime != runtime
                || !ownsOrProvisionallyOwns(typed)) {
            throw new IllegalArgumentException(
                    "component handle does not belong to this runtime");
        }
        record(new DisposeIntent(typed));
    }

    @Override
    public void dispose(ContextHandle context) {
        ensureOpen();
        Objects.requireNonNull(context, "context");
        if (!(context instanceof ContextHandleImpl handle)
                || handle.runtime != runtime) {
            throw new IllegalArgumentException(
                    "context handle does not belong to this runtime");
        }
        record(new ContextDisposeIntent(handle));
    }

    private void record(Intent intent) {
        if (closed.get()) {
            throw new IllegalStateException("transaction recorder is closed");
        }
        intents.add(intent);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("transaction recorder is closed");
        }
    }

    private ContextHandleImpl requireThisContext(ContextHandle context) {
        if (!(context instanceof ContextHandleImpl handle)
                || handle.runtime != runtime) {
            throw new IllegalArgumentException(
                    "context handle does not belong to this runtime");
        }
        return handle;
    }

    private boolean ownsOrProvisionallyOwns(MountHandleImpl handle) {
        for (Intent intent : intents) {
            if (intent instanceof MountIntent mount
                    && mount.handle().handleId().equals(handle.handleId())) {
                return true;
            }
        }
        return runtime.publishedState().index.componentHandles.get(handle.handleId())
                == handle;
    }

    private PreparedComponent<?> preparedFor(MountHandleImpl handle) {
        for (Intent intent : intents) {
            if (intent instanceof MountIntent mount
                    && mount.handle().handleId().equals(handle.handleId())) {
                return mount.prepared();
            }
        }
        ComponentRuntime runtimeComponent =
                runtime.publishedState().index.components.get(handle.handleId());
        if (runtimeComponent != null) {
            return runtimeComponent.prepared();
        }
        throw new IllegalArgumentException(
                "component handle does not belong to this runtime");
    }

    private ProvisionalConfig provisionalConfigFor(
            MountHandleImpl handle,
            PreparedComponent<?> prepared) {
        ProvisionalConfig provisional = provisionalConfigs.get(handle.handleId());
        if (provisional != null) {
            return provisional;
        }
        ComponentRuntime runtimeComponent =
                runtime.publishedState().index.components.get(handle.handleId());
        return runtimeComponent == null
                ? new ProvisionalConfig(prepared.config(), 1)
                : toProvisionalConfig(runtimeComponent.desiredState());
    }
    private ProvisionalConfig toProvisionalConfig(DesiredComponentState desired) {
        return new ProvisionalConfig(desired.config(), desired.revision());
    }

    private Object normalizeFor(PreparedComponent<?> prepared, Object rawConfig) {
        try {
            return Objects.requireNonNull(
                    prepared.normalize(rawConfig),
                    "config normalizer returned null");
        } catch (Exception error) {
            throw new PreparedComponent.InvalidConfigException(
                    LifecycleScopeImpl.safeError(error),
                    error);
        }
    }

    private record ProvisionalConfig(Object config, long revision) {
    }
}
