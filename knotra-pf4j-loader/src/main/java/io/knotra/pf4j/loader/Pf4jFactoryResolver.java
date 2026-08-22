package io.knotra.pf4j.loader;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.knotra.ConfiguredMountHandle;
import io.knotra.MountHandle;
import io.knotra.loader.ComponentFactoryResolver;
import io.knotra.loader.CompositeFactoryResolver;
import io.knotra.loader.ControlledMountContext;
import io.knotra.loader.ControlledMountException;
import io.knotra.loader.ControlledMountStrategy;
import io.knotra.loader.FactoryIdentity;
import io.knotra.loader.FactoryRef;
import io.knotra.loader.ReconfigureStrategy;
import io.knotra.loader.ResolvedFactory;
import io.knotra.loader.ResolvedFactory.FactoryKind;
import io.knotra.pf4j.ArtifactFactoryCatalogEntry;
import io.knotra.pf4j.ArtifactFactoryHandle;
import io.knotra.pf4j.ArtifactOperationException;
import io.knotra.pf4j.Pf4jArtifactAdapter;

/**
 * 把 PF4J 的活跃 factory catalog 适配为 Knotra Loader 的受控 resolver。
 *
 * <p>Root factory view 只用于读取稳定元数据并选择配置路径。Plain Mount 通过
 * no-config catalog 解析获得；类型化配置路径通过 catalog 的 typed resolution 捕获
 * {@code C}。Loader 记账中的 Object 配置只在桥接层的一个 Mount 边界恢复类型，然后
 * 挂载到 Loader 分配的 Context 与 mountId。</p>
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
        Optional<ArtifactFactoryHandle> root =
                adapter.factories().resolve(ref.factoryId());
        if (root.isPresent() && !versionMatches(ref, root.orElseThrow())) {
            return Optional.empty();
        }
        return root.flatMap(handle -> handle.noConfig()
                ? resolveNoConfig(ref, handle)
                : resolveConfigured(ref, handle));
    }

    private Optional<ResolvedFactory> resolveNoConfig(
            FactoryRef ref,
            ArtifactFactoryCatalogEntry expected) {
        String fingerprint = fingerprint(expected);
        return adapter.factories().resolveNoConfig(ref.factoryId())
                .filter(handle -> versionMatches(ref, handle))
                .filter(handle -> fingerprint(handle).equals(fingerprint))
                .map(handle -> new ResolvedFactory(
                        FactoryIdentity.fromRef(ref, fingerprint),
                        FactoryKind.PLAIN,
                        null,
                        noConfigMount(handle),
                        ReconfigureStrategy.unsupportedPlain()));
    }

    private static ControlledMountStrategy noConfigMount(
            ArtifactFactoryHandle.NoConfig handle) {
        return (context, config) -> mountSafely(
                () -> handle.mount(context.context(), context.mountId()));
    }

    private Optional<ResolvedFactory> resolveConfigured(
            FactoryRef ref,
            ArtifactFactoryHandle expected) {
        return resolveConfigured(ref, expected, expected.configType());
    }

    private <C> Optional<ResolvedFactory> resolveConfigured(
            FactoryRef ref,
            ArtifactFactoryCatalogEntry expected,
            Class<C> configType) {
        String fingerprint = fingerprint(expected);
        return adapter.factories().resolve(ref.factoryId(), configType)
                .filter(handle -> versionMatches(ref, handle))
                .filter(handle -> fingerprint(handle).equals(fingerprint))
                .map(handle -> new ResolvedFactory(
                        FactoryIdentity.fromRef(ref, fingerprint),
                        FactoryKind.CONFIGURED,
                        raw -> handle.decodeConfig(raw),
                        configuredMount(handle, configType),
                        ReconfigureStrategy.direct(FactoryKind.CONFIGURED)));
    }

    private static <C> ControlledMountStrategy configuredMount(
            ArtifactFactoryHandle.Configured<C> handle,
            Class<C> configType) {
        return (context, config) -> mountSafely(() -> {
            C typed = configType.cast(config);
            ConfiguredMountHandle<C> mounted =
                    handle.mount(context.context(), context.mountId(), typed);
            return mounted;
        });
    }

    private static CompletionStage<MountHandle> mountSafely(MountOperation operation) {
        try {
            MountHandle handle = operation.mount();
            if (handle == null) {
                return CompletableFuture.completedFuture(null);
            }
            return handle.whenSettled().thenApply(state -> handle);
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

    private static boolean versionMatches(FactoryRef ref, ArtifactFactoryCatalogEntry handle) {
        return ref.version().isEmpty() || ref.version().equals(handle.artifactVersion());
    }

    private static String fingerprint(ArtifactFactoryCatalogEntry handle) {
        return handle.artifactId()
                + "@" + handle.artifactVersion()
                + ":" + handle.artifactPath()
                + "#" + handle.factoryId()
                + ":" + handle.configTypeName();
    }

    @FunctionalInterface
    private interface MountOperation {
        MountHandle mount();
    }
}
