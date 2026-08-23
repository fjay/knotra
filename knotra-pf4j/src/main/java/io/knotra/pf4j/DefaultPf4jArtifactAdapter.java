package io.knotra.pf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import io.knotra.ComponentFactory;
import io.knotra.ConfigDecoder;
import io.knotra.ConfiguredMountHandle;
import io.knotra.ContextHandle;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.MountOptions;
import io.knotra.NoConfig;
import io.knotra.RuntimeTransaction;
import io.knotra.TransactionRejectedException;
import io.knotra.pf4j.spi.ExportedComponentFactory;
import io.knotra.pf4j.spi.RuntimeComponentProvider;
import org.pf4j.PluginDependency;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;

/**
 * {@link Pf4jArtifactAdapter} 的默认实现，也是 artifact 生命周期的唯一编排点。
 *
 * <p>加载在协调器中解析依赖闭包、按拓扑顺序加载并启动 PF4J 插件，然后只发布
 * guarded 工厂；组件挂载仍必须由宿主显式完成。卸载先进入 drain，停止新挂载、等待
 * in-flight 挂载、按依赖闭包 dispose owned handle，最后停止并卸载 PF4J 插件。任何
 * 清理失败都会保留 {@code DRAIN_FAILED} 与诊断，供后续 close/retryDrain 重试。</p>
 */
final class DefaultPf4jArtifactAdapter implements Pf4jArtifactAdapter {

    private final PluginManager pluginManager;
    private final ArtifactCoordinator coordinator;
    private final KnotraRuntime runtime;
    private final KnotraClassLoaderPolicy classLoaderPolicy;
    private final ManagedArtifactStore store = new ManagedArtifactStore();
    private final Pf4jClosureResolver closureResolver;
    private final ArtifactDrainService drainService;
    private final CatalogFacade factoryCatalog =
            new CatalogFacade(this::catalogEntries, this::activeFactory);
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final Object closeLock = new Object();
    private CompletableFuture<Void> closeFuture;

    DefaultPf4jArtifactAdapter(
            Path pluginsRoot,
            KnotraRuntime runtime,
            Set<String> sharedContractPackages) {
        Objects.requireNonNull(pluginsRoot, "pluginsRoot");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.classLoaderPolicy = KnotraClassLoaderPolicy.forHost(sharedContractPackages);
        this.pluginManager = new SharedContractPluginManager(pluginsRoot, classLoaderPolicy);
        this.coordinator = new ArtifactCoordinator();
        this.closureResolver = new Pf4jClosureResolver(pluginManager);
        this.drainService = new ArtifactDrainService(
                pluginManager,
                coordinator,
                runtime,
                store,
                closeStarted::get);
    }

    DefaultPf4jArtifactAdapter(
            PluginManager pluginManager,
            ArtifactCoordinator coordinator,
            KnotraRuntime runtime,
            KnotraClassLoaderPolicy policy) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.classLoaderPolicy = Objects.requireNonNull(policy, "policy");
        this.closureResolver = new Pf4jClosureResolver(pluginManager);
        this.drainService = new ArtifactDrainService(
                pluginManager,
                coordinator,
                runtime,
                store,
                closeStarted::get);
    }

    @Override
    public CompletableFuture<ArtifactSnapshot> loadArtifactAsync(Path artifactPath) {
        Path target = Objects.requireNonNull(artifactPath, "artifactPath").toAbsolutePath().normalize();
        if (closeStarted.get() || coordinator.isStopped()) {
            return CompletableFuture.failedFuture(new IllegalStateException("adapter is closed"));
        }
        return coordinator.submit(() -> {
            // 只记录本次新加载的插件；复用 artifact 不能被目标加载失败回滚掉。
            LoadJournal journal = new LoadJournal();
            String targetId = null;
            try {
                // 先离线解析完整闭包与拓扑顺序，缺失/环/版本冲突在加载任何插件前失败。
                Pf4jClosureResolver.ArtifactClosure closure = closureResolver.resolve(target);
                targetId = closure.targetId();
                loadMissing(closure, journal);
                // 依赖先启动，目标插件最后一个启动，保证扩展发现看到的是可用闭包。
                for (String id : closure.loadOrder()) {
                    PluginState state = pluginManager.startPlugin(id);
                    if (state != PluginState.STARTED) {
                        throw new IllegalStateException("PF4J start returned " + state + " for " + id);
                    }
                }
                // 全闭包启动成功后才发布目录；加载/启动阶段不会给宿主留下可挂载句柄。
                for (String id : closure.loadOrder()) {
                    publishIfAbsent(id, id.equals(targetId));
                }
                return snapshotCoordinated(targetId);
            } catch (Throwable failure) {
                // 按加载逆序回滚本次变更；回滚本身失败则保留残余诊断，不伪造成功。
                rollback(journal, targetId, failure);
                String id = targetId == null ? "unknown" : targetId;
                String detail = FailureText.describe(failure);
                throw new ArtifactOperationException(
                        id,
                        "load",
                        "failed to load PF4J artifact " + target + ": " + detail);
            }
        });
    }

    @Override
    public CompletableFuture<Void> unloadArtifactAsync(String artifactId) {
        return drainService.drain(Objects.requireNonNull(artifactId, "artifactId"), "unload");
    }

    @Override
    public CompletableFuture<Void> retryDrainAsync(String artifactId) {
        return drainService.drain(Objects.requireNonNull(artifactId, "artifactId"), "retry-drain");
    }

    private List<ArtifactFactoryCatalogEntry> catalogEntries() {
        return coordinator.submit(store::catalogEntries).join();
    }

    private ManagedFactory activeFactory(String factoryId) {
        Objects.requireNonNull(factoryId, "factoryId");
        return coordinator.submit(() -> store.activeFactory(factoryId)).join();
    }

    @Override
    public ArtifactFactoryCatalog factories() {
        return factoryCatalog;
    }

    <T> T coordinateRead(java.util.function.Supplier<T> action) {
        return coordinator.submit(action).join();
    }

    @Override
    public List<ArtifactSnapshot> artifacts() {
        return coordinator.submit(() -> {
            List<String> ids = store.artifactIds();
            return ids.stream().map(this::snapshotCoordinated).toList();
        }).join();
    }

    @Override
    public List<ArtifactSnapshot> artifactsInState(ArtifactState state) {
        Objects.requireNonNull(state, "state");
        return artifacts().stream().filter(artifact -> artifact.state() == state).toList();
    }

    @Override
    public Optional<ArtifactSnapshot> artifact(String artifactId) {
        Objects.requireNonNull(artifactId, "artifactId");
        if (coordinator.isStopped()) {
            return store.terminalSnapshot(artifactId);
        }
        return coordinator.submit(() -> {
            if (!store.containsArtifact(artifactId)) {
                return Optional.<ArtifactSnapshot>empty();
            }
            return Optional.ofNullable(snapshotCoordinated(artifactId));
        }).join();
    }

    @Override
    public Optional<ArtifactDiagnostic> diagnostic(String artifactId) {
        Objects.requireNonNull(artifactId, "artifactId");
        if (coordinator.isStopped()) {
            return store.stoppedDiagnostic(artifactId);
        }
        return coordinator.submit(() -> {
            List<ArtifactOwnership> ownership = ownershipCoordinated(artifactId);
            return store.diagnostic(artifactId, ownership);
        }).join();
    }

    @Override
    public List<ArtifactOwnership> ownership(String artifactId) {
        Objects.requireNonNull(artifactId, "artifactId");
        if (coordinator.isStopped()) {
            return List.of();
        }
        return coordinator.submit(() -> ownershipCoordinated(artifactId)).join();
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        CompletableFuture<Void> attempt;
        synchronized (closeLock) {
            if (closeFuture != null) {
                return closeFuture;
            }
            closeStarted.set(true);
            attempt = new CompletableFuture<>();
            closeFuture = attempt;
        }

        List<String> ids;
        try {
            ids = coordinator.submit(store::activeArtifactIds).join();
        } catch (Throwable failure) {
            clearFailedCloseAttempt(attempt);
            attempt.completeExceptionally(failure);
            return attempt;
        }

        if (ids.isEmpty()) {
            coordinator.stop();
            attempt.complete(null);
            return attempt;
        }
        List<CompletableFuture<Void>> drains = ids.stream()
                .map(id -> drainService.drain(id, "close-drain"))
                .toList();
        CompletableFuture.allOf(drains.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            if (failure != null) {
                // 保留协调器与诊断；调用方观察到失败后，新的 close 才能重试清理。
                clearFailedCloseAttempt(attempt);
                attempt.completeExceptionally(unwrap(failure));
                return;
            }
            coordinator.stop();
            attempt.complete(null);
        });
        return attempt;
    }

    private void clearFailedCloseAttempt(CompletableFuture<Void> attempt) {
        synchronized (closeLock) {
            if (closeFuture == attempt) {
                closeFuture = null;
            }
        }
    }

    MountHandle mount(
            ManagedFactory.NoConfigFactory handle,
            ContextHandle context,
            String mountId,
            ComponentFactory<NoConfig> factory) {
        return mount(handle, context, mountId, transaction -> transaction.mount(
                context, mountId, guarded(factory, handle), mountOptions(handle)));
    }

    <C> ConfiguredMountHandle<C> mount(
            ManagedFactory handle,
            ContextHandle context,
            String mountId,
            ComponentFactory<C> factory,
            C config) {
        return mount(handle, context, mountId, transaction -> transaction.mount(
                context, mountId, guarded(factory, handle), config, mountOptions(handle)));
    }

    private <C> ComponentFactory<C> guarded(
            ComponentFactory<C> factory,
            ManagedFactory handle) {
        return GuardedComponentFactory.wrap(
                factory,
                classLoaderPolicy,
                handle.artifact.metadata);
    }

    private MountOptions mountOptions(ManagedFactory handle) {
        return new MountOptions(
                handle.artifact.metadata.origin(),
                handle.artifact.metadata.metadata());
    }

    private <H extends MountHandle> H mount(
            ManagedFactory handle,
            ContextHandle context,
            String mountId,
            java.util.function.Function<RuntimeTransaction, H> commit) {
        Objects.requireNonNull(context, "context");
        if (mountId == null || mountId.isBlank()) {
            throw new IllegalArgumentException("mountId must not be blank");
        }
        beginMount(handle);
        boolean lostRace = false;
        H component = null;
        try {
            requireMountable(handle);
            // 核心事务在不持有适配器内存锁的情况下提交；核心内核控制启动。
            try {
                component = runtime.advanced().transact(commit::apply).value();
            } catch (TransactionRejectedException failure) {
                throw new ArtifactOperationException(
                        handle.artifact.artifactId,
                        "mount",
                        "Knotra rejected mount: " + failure.diagnostics(),
                        failure.diagnostics());
            }
            if (!store.recordDirectHandle(
                    handle.artifact,
                    component.handleId(),
                    component)) {
                lostRace = true;
            } else {
                return component;
            }
        } finally {
            if (!lostRace) {
                store.endMount(handle.artifact);
            }
        }

        // 在排空期间提交的挂载在补偿完成前保持所有权。
        H rejected = component;
        rejected.disposeAsync().whenComplete((ignored, disposalFailure) ->
                store.endMount(handle.artifact));
        throw new ArtifactOperationException(
                handle.artifact.artifactId,
                "mount",
                "artifact entered draining while mount was in flight");
    }

    private void beginMount(ManagedFactory handle) {
        if (closeStarted.get()) {
            throw new ArtifactOperationException(
                    handle.artifact.artifactId, "mount", "adapter is closed");
        }
        store.beginMount(handle);
    }

    private void requireMountable(ManagedFactory handle) {
        if (!store.isMountable(handle)) {
            throw new ArtifactOperationException(
                    handle.artifact.artifactId,
                    "mount",
                    "artifact is not accepting mounts");
        }
    }

    private void loadMissing(
            Pf4jClosureResolver.ArtifactClosure closure,
            LoadJournal journal) {
        for (String id : closure.loadOrder()) {
            // 只加载 PF4J 尚未持有的插件，并验证实际 ID，防止描述符与 jar 内容漂移。
            if (pluginManager.getPlugin(id) != null) {
                continue;
            }
            Pf4jClosureResolver.CatalogEntry entry = closure.entry(id);
            String loadedId = pluginManager.loadPlugin(entry.path());
            if (loadedId == null || loadedId.isBlank()) {
                throw new IllegalStateException("PF4J loadPlugin returned no id for " + entry.path());
            }
            journal.loaded(loadedId);
            if (!loadedId.equals(id)) {
                throw new IllegalStateException(
                        "PF4J loaded unexpected id: expected=" + id + ", actual=" + loadedId);
            }
        }
    }

    private void publishIfAbsent(String artifactId, boolean requireProvider) {
        PluginWrapper wrapper = requireWrapper(artifactId);
        Path path = wrapper.getPluginPath().toAbsolutePath().normalize();
        String version = wrapper.getDescriptor().getVersion();

        // 扩展发现会执行插件代码，因此候选对象在锁外构建，发布时再复查当前受管状态。
        store.publishCandidate(
                artifactId,
                existing -> requireSameManagedArtifact(existing, wrapper, path, version),
                () -> {
                    ManagedArtifact candidate = new ManagedArtifact(
                            artifactId,
                            version,
                            path,
                            dependencies(wrapper),
                            wrapper);
                    candidate.pf4jStateView = () -> pf4jState(artifactId);
                    return new ManagedArtifactStore.Candidate(
                            candidate,
                            discoverFactories(candidate, requireProvider));
                });
    }

    private void requireSameManagedArtifact(
            ManagedArtifact existing,
            PluginWrapper wrapper,
            Path path,
            String version) {
        PluginWrapper managedWrapper = existing.wrapper.get();
        if (managedWrapper == wrapper
                && existing.path.equals(path)
                && existing.version.equals(version)) {
            return;
        }
        throw new ArtifactOperationException(
                existing.artifactId,
                "load",
                "managed artifact conflicts with loaded PF4J wrapper: id="
                        + existing.artifactId
                        + ", managedPath=" + existing.path
                        + ", managedVersion=" + existing.version
                        + ", actualPath=" + path
                        + ", actualVersion=" + version);
    }

    private Set<String> dependencies(PluginWrapper wrapper) {
        return wrapper.getDescriptor().getDependencies().stream()
                .map(PluginDependency::getPluginId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private PluginWrapper requireWrapper(String artifactId) {
        PluginWrapper wrapper = pluginManager.getPlugin(artifactId);
        if (wrapper == null) {
            throw new IllegalStateException("PF4J artifact is not loaded: " + artifactId);
        }
        return wrapper;
    }

    private List<ManagedFactory> discoverFactories(
            ManagedArtifact artifact,
            boolean requireProvider) {
        List<RuntimeComponentProvider> providers = pluginManager.getExtensions(
                RuntimeComponentProvider.class,
                artifact.artifactId);
        if (providers.isEmpty()) {
            if (!requireProvider) {
                return List.of();
            }
            throw new IllegalStateException(
                    "artifact has no RuntimeComponentProvider entries: " + artifact.artifactId);
        }
        Set<String> localIds = new LinkedHashSet<>();
        List<ManagedFactory> result = new ArrayList<>();
        for (RuntimeComponentProvider provider : providers) {
            // 先保证 provider 扩展点身份来自宿主，再调用插件代码，避免被私有副本分派。
            classLoaderPolicy.validateInterface(
                    provider.getClass(),
                    RuntimeComponentProvider.class,
                    artifact.artifactId);
            Collection<ExportedComponentFactory<?>> exports = provider.factories();
            if (exports == null) {
                throw new IllegalStateException("provider returned null factories");
            }
            for (ExportedComponentFactory<?> exported : exports) {
                if (exported == null) {
                    throw new IllegalStateException("provider returned a null factory export");
                }
                addDiscoveredFactory(result, localIds, artifact, exported);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("provider returned no factories");
        }
        return result;
    }

    private <C> void addDiscoveredFactory(
            List<ManagedFactory> result,
            Set<String> localIds,
            ManagedArtifact artifact,
            ExportedComponentFactory<C> exported) {
        // 配置 token 是跨边界合约，必须在工厂进入目录前完成宿主身份校验。
        classLoaderPolicy.validateContractType(exported.configType(), artifact.artifactId);
        ComponentFactory<C> factory = exported.factory();
        ConfigDecoder<C> decoder = exported.decoder();
        classLoaderPolicy.validateInterface(
                decoder.getClass(),
                ConfigDecoder.class,
                artifact.artifactId);
        ComponentFactory<C> guarded = GuardedComponentFactory.wrap(
                factory,
                classLoaderPolicy,
                artifact.metadata);
        String factoryId = guarded.factoryId();
        if (!localIds.add(factoryId)) {
            throw new IllegalStateException(
                    "duplicate factory id inside artifact: " + factoryId);
        }
        store.requireFactoryIdAbsent(factoryId);
        result.add(ManagedFactory.create(
                this,
                artifact,
                factoryId,
                exported.configType(),
                decoder,
                guarded));
    }

    private void rollback(LoadJournal journal, String targetId, Throwable failure) {
        // 逆序停止并卸载本次 journal 记录的插件；残余插件必须留下 FAILED 诊断。
        List<String> rollbackFailures = new ArrayList<>();
        boolean residual = false;
        List<String> loaded = journal.loaded();
        for (int index = loaded.size() - 1; index >= 0; index--) {
            residual |= rollbackOne(loaded.get(index), rollbackFailures);
        }
        String message = rollbackFailures.isEmpty()
                ? FailureText.describe(failure)
                : FailureText.describe(failure) + "; rollback: " + String.join("; ", rollbackFailures);
        if (targetId != null) {
            store.recordLoadFailure(targetId, message, residual);
        }
    }

    private boolean rollbackOne(String id, List<String> rollbackFailures) {
        PluginWrapper wrapper = pluginManager.getPlugin(id);
        if (wrapper == null) {
            return false;
        }
        try {
            if (wrapper.getPluginState() == PluginState.STARTED
                    && pluginManager.stopPlugin(id) != PluginState.STOPPED) {
                rollbackFailures.add("PF4J stop returned non-STOPPED for " + id);
            }
        } catch (Throwable stopFailure) {
            rollbackFailures.add(FailureText.describe(stopFailure));
        }
        try {
            if (!pluginManager.unloadPlugin(id) && pluginManager.getPlugin(id) != null) {
                rollbackFailures.add("PF4J unload returned false for " + id);
            }
        } catch (Throwable unloadFailure) {
            rollbackFailures.add(FailureText.describe(unloadFailure));
        }
        if (pluginManager.getPlugin(id) == null) {
            store.discardUnloadedView(id);
            return false;
        }
        registerResidual(id, rollbackFailures);
        return true;
    }

    private void registerResidual(String artifactId, List<String> rollbackFailures) {
        PluginWrapper wrapper = pluginManager.getPlugin(artifactId);
        if (wrapper == null) {
            return;
        }
        store.registerResidual(
                artifactId,
                wrapper,
                candidateWrapper -> {
                    ManagedArtifact residual = new ManagedArtifact(
                            artifactId,
                            candidateWrapper.getDescriptor().getVersion(),
                            candidateWrapper.getPluginPath(),
                            dependencies(candidateWrapper),
                            candidateWrapper);
                    residual.pf4jStateView = () -> pf4jState(artifactId);
                    return residual;
                },
                String.join("; ", rollbackFailures));
    }

    private ArtifactSnapshot snapshotCoordinated(String artifactId) {
        List<ArtifactOwnership> ownership = ownershipCoordinated(artifactId);
        return store.snapshot(artifactId, ownership);
    }

    private List<ArtifactOwnership> ownershipCoordinated(String artifactId) {
        return store.ownershipCoordinated(
                artifactId,
                () -> runtime.advanced().snapshot());
    }

    private String pf4jState(String artifactId) {
        PluginWrapper wrapper = pluginManager.getPlugin(artifactId);
        return wrapper == null || wrapper.getPluginState() == null
                ? PluginState.UNLOADED.toString()
                : wrapper.getPluginState().toString();
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException completion
                && completion.getCause() != null
                ? completion.getCause()
                : failure;
    }

    private static final class LoadJournal {
        private final List<String> loadedIds = new ArrayList<>();

        void loaded(String artifactId) {
            if (!loadedIds.contains(artifactId)) {
                loadedIds.add(artifactId);
            }
        }

        List<String> loaded() {
            return List.copyOf(loadedIds);
        }
    }
}
