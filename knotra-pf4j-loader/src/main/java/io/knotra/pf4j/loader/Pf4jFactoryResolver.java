package io.knotra.pf4j.loader;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.knotra.ComponentHandle;
import io.knotra.ConfigDecoder;
import io.knotra.loader.ComponentFactoryResolver;
import io.knotra.loader.CompositeFactoryResolver;
import io.knotra.loader.ControlledMountContext;
import io.knotra.loader.ControlledMountException;
import io.knotra.loader.ControlledMountStrategy;
import io.knotra.loader.FactoryIdentity;
import io.knotra.loader.FactoryRef;
import io.knotra.loader.ReconfigureStrategy;
import io.knotra.loader.ResolvedFactory;
import io.knotra.pf4j.ArtifactFactoryHandle;
import io.knotra.pf4j.ArtifactOperationException;
import io.knotra.pf4j.Pf4jArtifactAdapter;

/**
 * 把 PF4J 的活跃 factory catalog 适配为 Knotra Loader 的受控 resolver。
 *
 * <p>桥接层拥有唯一的泛型捕获点：raw 配置先由 artifact export 的 decoder 转成 C，
 * 随后只能挂载到 Loader 分配的 Context 与 mountId。宿主不需要维护 factoryId 到 Class 的
 * 映射，也不会接触插件 ComponentFactory。</p>
 */
public final class Pf4jFactoryResolver implements ComponentFactoryResolver {
    private final Pf4jArtifactAdapter adapter;

    private Pf4jFactoryResolver(Pf4jArtifactAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    public static Pf4jFactoryResolver of(Pf4jArtifactAdapter adapter) {
        return new Pf4jFactoryResolver(adapter);
    }

    /** PF4J 优先，随后按参数顺序尝试 fallback resolver。 */
    public static ComponentFactoryResolver withFallbacks(
            Pf4jArtifactAdapter adapter,
            ComponentFactoryResolver... fallbacks) {
        Objects.requireNonNull(fallbacks, "fallbacks");
        ComponentFactoryResolver[] resolvers = new ComponentFactoryResolver[fallbacks.length + 1];
        resolvers[0] = of(adapter);
        System.arraycopy(fallbacks, 0, resolvers, 1, fallbacks.length);
        return CompositeFactoryResolver.of(resolvers);
    }

    @Override
    public Optional<ResolvedFactory> resolve(FactoryRef ref) {
        Objects.requireNonNull(ref, "ref");
        return adapter.factories().resolve(ref.factoryId())
                .filter(handle -> ref.version().isEmpty()
                        || ref.version().equals(handle.artifactVersion()))
                .map(handle -> capture(ref, handle));
    }

    private static <C> ResolvedFactory capture(
            FactoryRef ref,
            ArtifactFactoryHandle<C> handle) {
        String fingerprint = handle.artifactId()
                + "@" + handle.artifactVersion()
                + ":" + handle.artifactPath()
                + "#" + handle.factoryId()
                + ":" + handle.configType().getName();
        ConfigDecoder<Object> decoder = raw -> handle.decodeConfig(raw);
        ControlledMountStrategy mount = (slot, config) -> mountCaptured(slot, handle, config);
        return new ResolvedFactory(
                FactoryIdentity.fromRef(ref, fingerprint),
                decoder,
                mount,
                ReconfigureStrategy.direct());
    }

    private static <C> CompletionStage<ComponentHandle<?>> mountCaptured(
            ControlledMountContext slot,
            ArtifactFactoryHandle<C> handle,
            Object config) {
        try {
            C typed = handle.configType().cast(config);
            ComponentHandle<C> mounted = handle.mount(slot.context(), slot.mountId(), typed);
            return CompletableFuture.completedFuture(mounted)
                    .thenApply(value -> (ComponentHandle<?>) value);
        } catch (ArtifactOperationException error) {
            if (!error.diagnostics().isEmpty()) {
                return CompletableFuture.failedFuture(
                        new ControlledMountException(error.diagnostics()));
            }
            return CompletableFuture.failedFuture(error);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }
}
