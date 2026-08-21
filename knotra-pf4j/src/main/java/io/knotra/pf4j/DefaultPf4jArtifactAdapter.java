package io.knotra.pf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ComponentOrigin;
import io.knotra.ComponentState;
import io.knotra.ConfigSchema;
import io.knotra.ContextHandle;
import io.knotra.KnotraRuntime;
import io.knotra.MountOptions;
import io.knotra.MutationResult;
import io.knotra.RuntimeSnapshot;
import io.knotra.pf4j.spi.ExportedComponentFactory;
import io.knotra.pf4j.spi.RuntimeComponentProvider;
import org.pf4j.CompoundPluginDescriptorFinder;
import org.pf4j.DependencyResolver;
import org.pf4j.JarPluginRepository;
import org.pf4j.ManifestPluginDescriptorFinder;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginDependency;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.pf4j.PropertiesPluginDescriptorFinder;

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
    // 协调器串行化入口；drain 的异步等待会释放协调线程，因此内存状态仍需独立锁保护。
    private final Object stateLock = new Object();
    private final Map<String, ManagedArtifact> artifacts = new LinkedHashMap<>();
    private final Map<String, ArtifactDiagnostic> loadFailures = new LinkedHashMap<>();
    private final Map<String, ArtifactSnapshot> terminalSnapshots = new LinkedHashMap<>();
    private final Map<String, ManagedFactory<?>> catalog = new LinkedHashMap<>();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final Object closeLock = new Object();
    private CompletableFuture<Void> closeFuture;

    private final ArtifactFactoryResolver resolver = new ArtifactFactoryResolver() {
        @Override
        public Optional<ArtifactFactoryCatalogEntry> resolve(String factoryId) {
            Objects.requireNonNull(factoryId, "factoryId");
            return factoryCatalog().stream()
                    .filter(entry -> entry.factoryId().equals(factoryId))
                    .findFirst();
        }

        @Override
        public <C> Optional<ArtifactFactoryHandle<C>> resolve(
                String factoryId,
                Class<C> configType) {
            Objects.requireNonNull(configType, "configType");
            ManagedFactory<?> handle = activeFactory(factoryId);
            if (handle == null) {
                return Optional.empty();
            }
            if (!handle.configType().equals(configType)) {
                throw new IllegalArgumentException(
                        "factory " + factoryId + " config type is "
                                + handle.configType().getName()
                                + ", not " + configType.getName());
            }
            // 前面的精确 token 相等保证工厂泛型与调用方请求一致，这里只是恢复编译期类型。
            @SuppressWarnings("unchecked")
            ArtifactFactoryHandle<C> typed = (ArtifactFactoryHandle<C>) handle;
            return Optional.of(typed);
        }

        @Override
        public List<ArtifactFactoryCatalogEntry> handles() {
            return factoryCatalog();
        }
    };
    DefaultPf4jArtifactAdapter(
            Path pluginsRoot,
            KnotraRuntime runtime,
            Set<String> sharedContractPackages) {
        Objects.requireNonNull(pluginsRoot, "pluginsRoot");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.classLoaderPolicy = KnotraClassLoaderPolicy.forHost(sharedContractPackages);
        this.pluginManager = new SharedContractPluginManager(pluginsRoot, classLoaderPolicy);
        this.coordinator = new ArtifactCoordinator();
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
    }

    @Override
    public CompletableFuture<ArtifactSnapshot> loadArtifact(Path artifactPath) {
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
                ArtifactClosure closure = resolveClosure(target);
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
    public CompletableFuture<Void> unloadArtifact(String artifactId) {
        return drain(Objects.requireNonNull(artifactId, "artifactId"), "unload");
    }

    @Override
    public CompletableFuture<Void> retryDrain(String artifactId) {
        return drain(Objects.requireNonNull(artifactId, "artifactId"), "retry-drain");
    }

    @Override
    public List<ArtifactFactoryCatalogEntry> factoryCatalog() {
        return coordinator.submit(() -> {
            synchronized (stateLock) {
                List<ArtifactFactoryCatalogEntry> entries = new ArrayList<>();
                for (ManagedFactory<?> factory : catalog.values()) {
                    entries.add(new ArtifactFactoryCatalogView(
                            factory.artifactId(),
                            factory.artifactVersion(),
                            factory.artifactPath(),
                            factory.factoryId(),
                            factory.configTypeName()));
                }
                return List.copyOf(entries);
            }
        }).join();
    }

    private ManagedFactory<?> activeFactory(String factoryId) {
        Objects.requireNonNull(factoryId, "factoryId");
        return coordinator.submit(() -> {
            synchronized (stateLock) {
                return catalog.get(factoryId);
            }
        }).join();
    }


    @Override
    public ArtifactFactoryResolver resolver() {
        return resolver;
    }

    <T> T coordinateRead(java.util.function.Supplier<T> action) {
        return coordinator.submit(action).join();
    }

    @Override
    public List<ArtifactSnapshot> artifacts() {
        return coordinator.submit(() -> {
            List<String> ids;
            synchronized (stateLock) {
                ids = List.copyOf(artifacts.keySet());
            }
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
            synchronized (stateLock) {
                return Optional.ofNullable(terminalSnapshots.get(artifactId));
            }
        }
        return coordinator.submit(() -> {
            synchronized (stateLock) {
                if (!artifacts.containsKey(artifactId)) {
                    return Optional.<ArtifactSnapshot>empty();
                }
            }
            return Optional.of(snapshotCoordinated(artifactId));
        }).join();
    }

    @Override
    public Optional<ArtifactDiagnostic> diagnostic(String artifactId) {
        Objects.requireNonNull(artifactId, "artifactId");
        if (coordinator.isStopped()) {
            synchronized (stateLock) {
                ManagedArtifact artifact = artifacts.get(artifactId);
                return Optional.ofNullable(artifact == null
                        ? loadFailures.get(artifactId)
                        : artifact.diagnostic(List.of()));
            }
        }
        return coordinator.submit(() -> {
            List<ArtifactOwnership> ownership = ownershipCoordinated(artifactId);
            synchronized (stateLock) {
                ManagedArtifact artifact = artifacts.get(artifactId);
                if (artifact != null) {
                    return Optional.of(artifact.diagnostic(ownership));
                }
                return Optional.ofNullable(loadFailures.get(artifactId));
            }
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
            ids = coordinator.submit(() -> {
                synchronized (stateLock) {
                    return artifacts.values().stream()
                            .filter(artifact -> artifact.state != ArtifactState.UNLOADED)
                            .map(artifact -> artifact.artifactId)
                            .toList();
                }
            }).join();
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
                .map(id -> drain(id, "close-drain"))
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

    <C> ComponentHandle<C> mount(
            ManagedFactory<C> handle,
            ContextHandle context,
            String mountId,
            ComponentFactory<C> factory,
            C config) {
        Objects.requireNonNull(context, "context");
        if (mountId == null || mountId.isBlank()) {
            throw new IllegalArgumentException("mountId must not be blank");
        }
        beginMount(handle);
        boolean lostRace = false;
        ComponentHandle<C> component = null;
        try {
            synchronized (stateLock) {
                requireMountableLocked(handle);
            }
            ComponentFactory<C> guarded = GuardedComponentFactory.wrap(
                    factory,
                    classLoaderPolicy,
                    handle.artifact.metadata);
            // 提交 Core 事务不持有内存状态锁；组件启动仍由 Core 控制在协调器之外。
            MutationResult<ComponentHandle<C>> result = runtime.mutate(mutation ->
                    mutation.mount(
                            context,
                            mountId,
                            guarded,
                            config,
                            new MountOptions(
                                    handle.artifact.metadata.origin(),
                                    handle.artifact.metadata.metadata())));
            if (!result.committed()) {
                throw new ArtifactOperationException(
                        handle.artifact.artifactId,
                        "mount",
                        "Knotra rejected mount: " + result.diagnostics());
            }
            component = result.value();
            synchronized (stateLock) {
                // Core 已提交的句柄即使撞上并发 drain，也必须先记入所有权再决定回滚。
                handle.artifact.directHandles.put(component.handleId(), component);
                if (handle.artifact.acceptingMounts
                        && handle.artifact.state == ArtifactState.ACTIVE) {
                    return component;
                }
                lostRace = true;
            }
        // 正常路径在此解除 in-flight 计数；输给 drain 的路径等补偿 dispose 完成后解除。
        } finally {
            if (!lostRace) {
                endMount(handle.artifact);
            }
        }

        // mount 提交后撞上 drain：先保留所有权，异步 dispose 完成前不能卸载插件。
        ComponentHandle<C> rejected = component;
        rejected.dispose().whenComplete((ignored, disposalFailure) ->
                endMount(handle.artifact));
        throw new ArtifactOperationException(
                handle.artifact.artifactId,
                "mount",
                "artifact entered draining while mount was in flight");
    }

    private void beginMount(ManagedFactory<?> handle) {
        synchronized (stateLock) {
            if (closeStarted.get()) {
                throw new ArtifactOperationException(
                        handle.artifact.artifactId, "mount", "adapter is closed");
            }
            requireMountableLocked(handle);
            handle.artifact.mountsInFlight++;
        }
    }

    private void requireMountableLocked(ManagedFactory<?> handle) {
        if (handle.factory == null
                || !handle.artifact.acceptingMounts
                || handle.artifact.state != ArtifactState.ACTIVE) {
            throw new ArtifactOperationException(
                    handle.artifact.artifactId,
                    "mount",
                    "artifact is not accepting mounts");
        }
    }

    private void endMount(ManagedArtifact artifact) {
        synchronized (stateLock) {
            artifact.mountsInFlight = Math.max(0, artifact.mountsInFlight - 1);
            if (artifact.mountsInFlight == 0) {
                CompletableFuture<Void> future = artifact.mountsInFlightFuture;
                artifact.mountsInFlightFuture = null;
                if (future != null) {
                    future.complete(null);
                }
            }
        }
    }

    private ArtifactClosure resolveClosure(Path targetPath) {
        CompoundPluginDescriptorFinder descriptorFinder = new CompoundPluginDescriptorFinder()
                .add(new PropertiesPluginDescriptorFinder())
                .add(new ManifestPluginDescriptorFinder());
        Map<String, CatalogEntry> repository = new LinkedHashMap<>();
        Map<Path, CatalogEntry> byPath = new LinkedHashMap<>();
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        // 同时扫描配置 roots、目标所在目录和已加载 wrapper，才能复用依赖并识别同 ID 冲突。
        for (Path root : safePluginRoots()) {
            roots.add(root.toAbsolutePath().normalize());
        }
        roots.add(targetPath.getParent().toAbsolutePath().normalize());
        for (Path root : roots) {
            for (Path path : new JarPluginRepository(root).getPluginPaths()) {
                putRepositoryEntry(
                        repository,
                        byPath,
                        readDescriptor(descriptorFinder, path.toAbsolutePath().normalize()));
            }
        }
        for (PluginWrapper wrapper : pluginManager.getPlugins()) {
            putRepositoryEntry(repository, byPath, new CatalogEntry(
                    wrapper.getPluginPath().toAbsolutePath().normalize(),
                    wrapper.getDescriptor()));
        }

        CatalogEntry targetEntry = readDescriptor(descriptorFinder, targetPath);
        putRepositoryEntry(repository, byPath, targetEntry);
        String targetId = targetEntry.descriptor().getPluginId();

        Map<String, CatalogEntry> selected = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        collectClosure(targetId, repository, selected, new LinkedHashSet<>(), missing);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "missing required PF4J dependencies: " + String.join(", ", missing));
        }
        DependencyResolver.Result resolved = new DependencyResolver(pluginManager.getVersionManager())
                .resolve(selected.values().stream().map(CatalogEntry::descriptor).toList());
        if (resolved.hasCyclicDependency()) {
            throw new IllegalStateException("PF4J dependency cycle contains artifact " + targetId);
        }
        if (resolved.hasNotFoundDependencies()) {
            throw new IllegalStateException(
                    "missing required PF4J dependencies: " + resolved.getNotFoundDependencies());
        }
        if (resolved.hasWrongVersionDependencies()) {
            throw new IllegalStateException(
                    "incompatible PF4J dependencies: " + resolved.getWrongVersionDependencies());
        }
        Set<String> selectedIds = selected.keySet();
        List<String> order = resolved.getSortedPlugins().stream()
                .filter(selectedIds::contains)
                .toList();
        if (order.isEmpty() || !order.getLast().equals(targetId)) {
            throw new IllegalStateException("PF4J dependency order does not end at " + targetId);
        }
        return new ArtifactClosure(targetId, order, selected);
    }

    private CatalogEntry readDescriptor(
            CompoundPluginDescriptorFinder finder,
            Path path) {
        try {
            PluginDescriptor descriptor = finder.find(path);
            if (descriptor != null
                    && descriptor.getPluginId() != null
                    && !descriptor.getPluginId().isBlank()
                    && descriptor.getVersion() != null) {
                return new CatalogEntry(path, descriptor);
            }
        } catch (RuntimeException ignored) {
            // 统一落到下方稳定失败，避免把 finder 的各种运行时异常泄漏给宿主。
        }
        throw new IllegalStateException("cannot read a valid PF4J descriptor from " + path);
    }

    private void putRepositoryEntry(
            Map<String, CatalogEntry> repository,
            Map<Path, CatalogEntry> byPath,
            CatalogEntry entry) {
        CatalogEntry byId = repository.putIfAbsent(entry.descriptor().getPluginId(), entry);
        if (byId != null && !sameEntry(byId, entry)) {
            throw ambiguousTarget(entry.descriptor().getPluginId(), """
                    ambiguous PF4J repository entry: id=%s, firstPath=%s, firstVersion=%s, \
                    actualPath=%s, actualVersion=%s\
                    """.formatted(
                    entry.descriptor().getPluginId(),
                    byId.path(),
                    byId.descriptor().getVersion(),
                    entry.path(),
                    entry.descriptor().getVersion()));
        }
        CatalogEntry pathEntry = byPath.putIfAbsent(entry.path(), entry);
        if (pathEntry != null && !sameEntry(pathEntry, entry)) {
            throw ambiguousTarget(entry.descriptor().getPluginId(), """
                    ambiguous PF4J path entry: path=%s, firstId=%s, firstVersion=%s, \
                    actualId=%s, actualVersion=%s\
                    """.formatted(
                    entry.path(),
                    pathEntry.descriptor().getPluginId(),
                    pathEntry.descriptor().getVersion(),
                    entry.descriptor().getPluginId(),
                    entry.descriptor().getVersion()));
        }
    }

    private boolean sameEntry(CatalogEntry left, CatalogEntry right) {
        return left.path().equals(right.path())
                && left.descriptor().getPluginId().equals(right.descriptor().getPluginId())
                && left.descriptor().getVersion().equals(right.descriptor().getVersion());
    }

    private IllegalStateException ambiguousTarget(String id, String message) {
        return new IllegalStateException(message + ", artifactId=" + id);
    }

    private void collectClosure(
            String id,
            Map<String, CatalogEntry> repository,
            Map<String, CatalogEntry> selected,
            Set<String> visiting,
            Set<String> missing) {
        if (!visiting.add(id)) {
            throw new IllegalStateException("PF4J dependency cycle contains artifact " + id);
        }
        CatalogEntry entry = repository.get(id);
        if (entry == null) {
            missing.add(id);
            visiting.remove(id);
            return;
        }
        selected.put(id, entry);
        // optional 依赖只要在仓库中就参与解析；完全缺失时才允许被跳过。
        for (PluginDependency dependency : entry.descriptor().getDependencies()) {
            if (repository.containsKey(dependency.getPluginId()) || !dependency.isOptional()) {
                collectClosure(
                        dependency.getPluginId(),
                        repository,
                        selected,
                        visiting,
                        missing);
            }
        }
        visiting.remove(id);
    }

    private List<Path> safePluginRoots() {
        try {
            List<Path> roots = pluginManager.getPluginsRoots();
            if (roots == null) {
                return List.of();
            }
            return roots.stream().filter(Objects::nonNull).toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private void loadMissing(ArtifactClosure closure, LoadJournal journal) {
        for (String id : closure.loadOrder()) {
            // 只加载 PF4J 尚未持有的插件，并验证实际 ID，防止描述符与 jar 内容漂移。
            if (pluginManager.getPlugin(id) != null) {
                continue;
            }
            CatalogEntry entry = closure.entry(id);
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
        synchronized (stateLock) {
            ManagedArtifact existing = artifacts.get(artifactId);
            if (existing != null && existing.state != ArtifactState.UNLOADED) {
                requireSameManagedArtifact(existing, wrapper, path, version);
                return;
            }
        }

        ManagedArtifact candidate = new ManagedArtifact(
                artifactId,
                version,
                path,
                dependencies(wrapper),
                wrapper);
        candidate.pf4jStateView = () -> pf4jState(artifactId);
        List<ManagedFactory<?>> discovered = discoverFactories(candidate, requireProvider);
        // 锁外发现期间同 ID 可能已被并发协调发布；只有最终检查通过才提交目录。
        synchronized (stateLock) {
            ManagedArtifact existing = artifacts.get(artifactId);
            if (existing != null && existing.state != ArtifactState.UNLOADED) {
                requireSameManagedArtifact(existing, wrapper, path, version);
                return;
            }
            artifacts.put(artifactId, candidate);
            loadFailures.remove(artifactId);
            for (ManagedFactory<?> factory : discovered) {
                candidate.factories.add(factory);
                candidate.factoriesById.put(factory.factoryId, factory);
                candidate.factoryIdHistory.add(factory.factoryId);
                catalog.put(factory.factoryId, factory);
            }
        }
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

    private List<ManagedFactory<?>> discoverFactories(
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
        List<ManagedFactory<?>> result = new ArrayList<>();
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
            List<ManagedFactory<?>> result,
            Set<String> localIds,
            ManagedArtifact artifact,
            ExportedComponentFactory<C> exported) {
        // 配置 token 是跨边界合约，必须在工厂进入目录前完成宿主身份校验。
        classLoaderPolicy.validateContractType(exported.configType(), artifact.artifactId);
        ComponentFactory<C> factory = exported.factory();
        Optional<ConfigSchema<C>> configSchema = factory.configSchema();
        ComponentFactory<C> guarded = GuardedComponentFactory.wrap(
                factory,
                classLoaderPolicy,
                artifact.metadata);
        String factoryId = guarded.factoryId();
        if (!localIds.add(factoryId)) {
            throw new IllegalStateException(
                    "duplicate factory id inside artifact: " + factoryId);
        }
        synchronized (stateLock) {
            if (catalog.containsKey(factoryId)) {
                throw new IllegalStateException(
                        "factory id is already cataloged: " + factoryId);
            }
        }
        result.add(new ManagedFactory<>(
                this, artifact, factoryId, exported.configType(), guarded, configSchema));
    }

    private void rollback(LoadJournal journal, String targetId, Throwable failure) {
        // 逆序停止并卸载本次 journal 记录的插件；残余插件必须留下 FAILED 诊断。
        List<String> rollbackFailures = new ArrayList<>();
        boolean residual = false;
        List<String> loaded = journal.loaded();
        for (int index = loaded.size() - 1; index >= 0; index--) {
            String id = loaded.get(index);
            PluginWrapper wrapper = pluginManager.getPlugin(id);
            if (wrapper == null) {
                continue;
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
                synchronized (stateLock) {
                    ManagedArtifact artifact = artifacts.get(id);
                    if (artifact != null) {
                        artifact.unloadView();
                    }
                }
            } else {
                residual = true;
                registerResidual(id, rollbackFailures);
            }
        }
        String message = rollbackFailures.isEmpty()
                ? FailureText.describe(failure)
                : FailureText.describe(failure) + "; rollback: " + String.join("; ", rollbackFailures);
        if (targetId != null) {
            synchronized (stateLock) {
                ManagedArtifact existing = artifacts.get(targetId);
                if (existing != null && existing.state != ArtifactState.UNLOADED) {
                    existing.lastError = message;
                    return;
                }
                loadFailures.put(targetId, new ArtifactDiagnostic(
                        targetId,
                        ArtifactState.FAILED,
                        residual ? "load-rollback-residual" : "load-rollback",
                        List.of(),
                        List.of(),
                        Optional.of(message),
                        List.of()));
            }
        }
    }

    private void registerResidual(String artifactId, List<String> rollbackFailures) {
        PluginWrapper wrapper = pluginManager.getPlugin(artifactId);
        if (wrapper == null) {
            return;
        }
        synchronized (stateLock) {
            ManagedArtifact artifact = artifacts.get(artifactId);
            if (artifact == null) {
                artifact = new ManagedArtifact(
                        artifactId,
                        wrapper.getDescriptor().getVersion(),
                        wrapper.getPluginPath(),
                        dependencies(wrapper),
                        wrapper);
                artifact.pf4jStateView = () -> pf4jState(artifactId);
                artifacts.put(artifactId, artifact);
            }
            artifact.fail(
                    "load-rollback-residual",
                    String.join("; ", rollbackFailures));
            loadFailures.put(artifactId, artifact.diagnostic(List.of()));
        }
    }

    private CompletableFuture<Void> drain(String artifactId, String phase) {
        if (closeStarted.get() && phase.equals("unload")) {
            return CompletableFuture.failedFuture(new IllegalStateException("adapter is closing"));
        }
        if (coordinator.isStopped()) {
            return CompletableFuture.failedFuture(new IllegalStateException("adapter is closed"));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        // 先在协调器上完成状态切换；后续等待和 dispose 异步执行，不占用唯一协调线程。
        CompletableFuture<Void> scheduled = coordinator.execute(() -> {
            DrainRequest request;
            synchronized (stateLock) {
                ManagedArtifact target = artifacts.get(artifactId);
                if (target == null) {
                    result.completeExceptionally(new ArtifactOperationException(
                            artifactId, phase, "artifact is not managed"));
                    return;
                }
                // 复用同一个 drain future，使并发 unload/retry 观察到同一结果。
                if (target.state == ArtifactState.DRAINING && target.drainFuture != null) {
                    target.drainFuture.whenComplete((ignored, error) -> {
                        if (error == null) {
                            result.complete(null);
                        } else {
                            result.completeExceptionally(error);
                        }
                    });
                    return;
                }
                if (target.state == ArtifactState.UNLOADED) {
                    result.complete(null);
                    return;
                }
                try {
                    request = beginDrainLocked(target, result, phase);
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                    return;
                }
            }
            waitAndDispose(request);
        });
        scheduled.whenComplete((ignored, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private DrainRequest beginDrainLocked(
            ManagedArtifact target,
            CompletableFuture<Void> result,
            String phase) {
        // 卸载依赖会先 drain 全部未卸载下游 artifact，避免下游仍引用已释放插件类。
        List<ManagedArtifact> closure = dependentClosure(target);
        for (ManagedArtifact artifact : closure) {
            artifact.state = ArtifactState.DRAINING;
            artifact.transition = phase;
            artifact.acceptingMounts = false;
            artifact.drainFuture = result;
            for (ManagedFactory<?> factory : artifact.factories) {
                catalog.remove(factory.factoryId);
            }
            artifact.invalidateFactories();
        }
        return new DrainRequest(closure, result, phase);
    }

    private List<ManagedArtifact> dependentClosure(ManagedArtifact target) {
        List<ManagedArtifact> order = new ArrayList<>();
        collectDependents(target.artifactId, new LinkedHashSet<>(), order, new LinkedHashSet<>());
        return order;
    }

    private void collectDependents(
            String artifactId,
            Set<String> visited,
            List<ManagedArtifact> order,
            Set<String> visiting) {
        if (!visiting.add(artifactId)) {
            throw new IllegalStateException("PF4J dependent cycle contains artifact " + artifactId);
        }
        for (ManagedArtifact candidate : artifacts.values()) {
            if (candidate.state != ArtifactState.UNLOADED
                    && candidate.dependencies.contains(artifactId)) {
                collectDependents(candidate.artifactId, visited, order, visiting);
            }
        }
        visiting.remove(artifactId);
        if (visited.add(artifactId)) {
            ManagedArtifact self = artifacts.get(artifactId);
            if (self != null && self.state != ArtifactState.UNLOADED) {
                order.add(self);
            }
        }
    }

    private void waitAndDispose(DrainRequest request) {
        // drain 的顺序是等待 in-flight 挂载，再刷新归属并异步 dispose owned root。
        List<CompletableFuture<Void>> waits = request.artifacts().stream()
                .map(this::waitForMounts)
                .toList();
        CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, waitFailure) -> {
                    if (waitFailure != null) {
                        completeDrainFailure(request, waitFailure);
                        return;
                    }
                    disposeOwnedHandles(request);
                });
    }

    private CompletableFuture<Void> waitForMounts(ManagedArtifact artifact) {
        synchronized (stateLock) {
            if (artifact.mountsInFlight == 0) {
                return CompletableFuture.completedFuture(null);
            }
            if (artifact.mountsInFlightFuture == null) {
                artifact.mountsInFlightFuture = new CompletableFuture<>();
            }
            return artifact.mountsInFlightFuture;
        }
    }

    private void disposeOwnedHandles(DrainRequest request) {
        for (ManagedArtifact artifact : request.artifacts()) {
            refreshOwnership(artifact.artifactId);
        }
        // 只 dispose 适配器直接提交的根；来源标记像 artifact 的宿主根不能被悄悄夺走。
        ArtifactOperationException missingRoots = verifyKnownArtifactRoots(request);
        if (missingRoots != null) {
            completeDrainFailure(request, missingRoots);
            return;
        }
        List<CompletableFuture<ComponentState>> disposals = new ArrayList<>();
        for (ManagedArtifact artifact : request.artifacts()) {
            for (ComponentHandle<?> handle : artifact.rootHandles()) {
                if (handle.state() == ComponentState.DISPOSED) {
                    continue;
                }
                disposals.add(disposeRootHandle(handle));
            }
        }
        if (disposals.isEmpty()) {
            finishDrain(request);
            return;
        }
        CompletableFuture.allOf(disposals.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, disposalFailure) -> {
                    for (ManagedArtifact artifact : request.artifacts()) {
                        refreshOwnership(artifact.artifactId);
                    }
                    boolean unsettled = request.artifacts().stream()
                            .flatMap(artifact -> artifact.rootHandles().stream())
                            .anyMatch(handle -> handle.state() != ComponentState.DISPOSED);
                    if (disposalFailure != null || unsettled) {
                        completeDrainFailure(
                                request,
                                disposalFailure == null
                                        ? new ArtifactOperationException(
                                                request.targetId(),
                                                "drain",
                                                "one or more artifact components failed teardown")
                                        : disposalFailure);
                        return;
                    }
                    finishDrain(request);
                });
    }

    private CompletableFuture<ComponentState> disposeRootHandle(ComponentHandle<?> handle) {
        boolean failedDisposedGoal = handle.state() == ComponentState.FAILED
                && handle.goal() == io.knotra.ComponentGoal.DISPOSED;
        // FAILED 且目标是 DISPOSED 时，只剩可重试的清理，不应重新启动组件。
        CompletableFuture<ComponentState> attempt = failedDisposedGoal
                ? handle.retry().toCompletableFuture()
                : handle.dispose().toCompletableFuture();
        return attempt.exceptionallyCompose(error -> {
            if (!isRuntimeClosing()) {
                return CompletableFuture.failedFuture(error);
            }
            // runtime.close 已拥有该清理；适配器等待其收敛，避免重复 dispose 争抢。
            return handle.whenSettled().toCompletableFuture();
        });
    }

    private boolean isRuntimeClosing() {
        String rootId = runtime.rootContext().contextId();
        return runtime.snapshot().contexts().stream()
                .filter(context -> context.contextId().equals(rootId))
                .findFirst()
                .map(context -> context.state() == io.knotra.ContextState.DISPOSING
                        || context.state() == io.knotra.ContextState.DISPOSED)
                .orElse(false);
    }

    private ArtifactOperationException verifyKnownArtifactRoots(DrainRequest request) {
        Set<String> artifactIds = request.artifacts().stream()
                .map(artifact -> artifact.artifactId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        RuntimeSnapshot snapshot = runtime.snapshot();
        List<String> missing = new ArrayList<>();
        synchronized (stateLock) {
            for (RuntimeSnapshot.ComponentSnapshot component : snapshot.components()) {
                ComponentOrigin origin = component.origin();
                if (origin.kind() != ComponentOrigin.Kind.ARTIFACT
                        || !artifactIds.contains(origin.sourceId())
                        || component.parentHandleId() != null) {
                    continue;
                }
                ManagedArtifact artifact = artifacts.get(origin.sourceId());
                if (artifact == null || !artifact.directHandles.containsKey(component.handleId())) {
                    missing.add(component.handleId());
                }
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        return new ArtifactOperationException(
                request.targetId(),
                "drain",
                "artifact snapshot contains roots without adapter ownership: "
                        + missing.stream().sorted().toList());
    }

    private void completeDrainFailure(DrainRequest request, Throwable failure) {
        synchronized (stateLock) {
            for (ManagedArtifact artifact : request.artifacts()) {
                artifact.state = ArtifactState.DRAIN_FAILED;
                artifact.transition = "drain-failed";
                artifact.acceptingMounts = false;
                artifact.lastError = FailureText.describe(failure);
                artifact.drainFuture = null;
            }
        }
        request.result().completeExceptionally(failure);
    }

    private void finishDrain(DrainRequest request) {
        // 所有 dispose settled 后重新进入协调器；最终 stop/unload 不能与读或其他 drain 交错。
        CompletableFuture<Void> scheduled = coordinator.execute(() -> {
            try {
                stopAndUnload(request);
                synchronized (stateLock) {
                    for (ManagedArtifact artifact : request.artifacts()) {
                        artifact.state = ArtifactState.UNLOADED;
                        artifact.transition = "unloaded";
                        terminalSnapshots.put(artifact.artifactId, artifact.snapshot(List.of()));
                        artifact.unloadView();
                    }
                }
                request.result().complete(null);
            } catch (Throwable failure) {
                synchronized (stateLock) {
                    for (ManagedArtifact artifact : request.artifacts()) {
                        if (artifact.state == ArtifactState.UNLOADED) {
                            continue;
                        }
                        artifact.state = ArtifactState.DRAIN_FAILED;
                        artifact.transition = "pf4j-unload-failed";
                        artifact.acceptingMounts = false;
                        artifact.lastError = FailureText.describe(failure);
                        artifact.drainFuture = null;
                    }
                }
                request.result().completeExceptionally(failure);
            }
        });
        scheduled.whenComplete((ignored, error) -> {
            if (error != null) {
                request.result().completeExceptionally(error);
            }
        });
    }

    private void stopAndUnload(DrainRequest request) {
        for (ManagedArtifact artifact : request.artifacts()) {
            PluginWrapper wrapper = pluginManager.getPlugin(artifact.artifactId);
            PluginState current = wrapper == null || wrapper.getPluginState() == null
                    ? PluginState.UNLOADED
                    : wrapper.getPluginState();
            if (current == PluginState.STARTED) {
                PluginState stopped = pluginManager.stopPlugin(artifact.artifactId);
                if (stopped != PluginState.STOPPED) {
                    throw new IllegalStateException(
                            "PF4J stop returned " + stopped + " for " + artifact.artifactId);
                }
            }
            if (!pluginManager.unloadPlugin(artifact.artifactId)
                    && pluginManager.getPlugin(artifact.artifactId) != null) {
                throw new IllegalStateException(
                        "PF4J unload returned false for " + artifact.artifactId);
            }
        }
    }

    private ArtifactSnapshot snapshotCoordinated(String artifactId) {
        List<ArtifactOwnership> ownership = ownershipCoordinated(artifactId);
        synchronized (stateLock) {
            ManagedArtifact artifact = artifacts.get(artifactId);
            return artifact == null ? null : artifact.snapshot(ownership);
        }
    }

    private List<ArtifactOwnership> ownershipCoordinated(String artifactId) {
        ManagedArtifact artifact;
        synchronized (stateLock) {
            artifact = artifacts.get(artifactId);
        }
        if (artifact == null || artifact.state == ArtifactState.UNLOADED) {
            return List.of();
        }
        RuntimeSnapshot snapshot = runtime.snapshot();
        Map<String, RuntimeSnapshot.ComponentSnapshot> byId = snapshot.components().stream()
                .collect(Collectors.toMap(
                        RuntimeSnapshot.ComponentSnapshot::handleId,
                        item -> item,
                        (left, right) -> right,
                        LinkedHashMap::new));
        synchronized (stateLock) {
            // Runtime 快照是存活句柄的权威来源；已 dispose 且消失的本地记录可以清理。
            artifact.directHandles.keySet().removeIf(handleId ->
                    artifact.directHandles.get(handleId) != null
                            && artifact.directHandles.get(handleId).state() == ComponentState.DISPOSED
                            && !byId.containsKey(handleId));
            List<ArtifactOwnership> result = new ArrayList<>();
            for (RuntimeSnapshot.ComponentSnapshot component : snapshot.components()) {
                ComponentOrigin origin = component.origin();
                if (origin.kind() != ComponentOrigin.Kind.ARTIFACT
                        || !origin.sourceId().equals(artifactId)) {
                    continue;
                }
                ManagedFactory<?> direct = component.parentHandleId() == null
                        ? null
                        : null;
                String factoryId = direct == null
                        ? component.factoryId()
                        : direct.factoryId;
                result.add(new ArtifactOwnership(
                        artifactId,
                        factoryId,
                        component.handleId(),
                        component.mountId(),
                        component.parentHandleId(),
                        component.state()));
            }
            return result;
        }
    }

    private void refreshOwnership(String artifactId) {
        ownershipCoordinated(artifactId);
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

    private record CatalogEntry(Path path, PluginDescriptor descriptor) {
    }

    private record ArtifactClosure(
            String targetId,
            List<String> loadOrder,
            Map<String, CatalogEntry> entries) {

        CatalogEntry entry(String artifactId) {
            CatalogEntry entry = entries.get(artifactId);
            if (entry == null) {
                throw new IllegalStateException("artifact is not in closure: " + artifactId);
            }
            return entry;
        }
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

    private record DrainRequest(
            List<ManagedArtifact> artifacts,
            CompletableFuture<Void> result,
            String phase) {

        String targetId() {
            return artifacts.getLast().artifactId;
        }
    }
}
