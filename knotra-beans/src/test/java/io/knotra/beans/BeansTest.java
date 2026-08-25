package io.knotra.beans;

import io.knotra.AsyncCloseable;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement.CapabilityBinding;
import io.knotra.CapabilityRequirement.Mode;
import io.knotra.ComponentFactory;
import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.DiagnosticCode;
import io.knotra.DynamicCapability;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.Publication;
import io.knotra.PublicationChange;
import io.knotra.TransactionRejectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BeansTest {

    static final CapabilityKey<String> D0 = CapabilityKey.of("beans-d0", String.class);
    static final CapabilityKey<String> D1 = CapabilityKey.of("beans-d1", String.class);
    static final CapabilityKey<String> D2 = CapabilityKey.of("beans-d2", String.class);
    static final CapabilityKey<String> D3 = CapabilityKey.of("beans-d3", String.class);
    static final CapabilityKey<String> D4 = CapabilityKey.of("beans-d4", String.class);
    static final CapabilityKey<String> D5 = CapabilityKey.of("beans-d5", String.class);
    static final CapabilityKey<String> D6 = CapabilityKey.of("beans-d6", String.class);
    static final CapabilityKey<String> OPT = CapabilityKey.of("beans-opt", String.class);
    static final Duration WAIT = Duration.ofSeconds(10);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void providerRebindCreatesFreshBeanAndClosesOldBean() throws Exception {
        CapabilityKey<String> dep = CapabilityKey.of("rebind-dep", String.class);
        CapabilityKey<Service> out = CapabilityKey.of("rebind-service", Service.class);
        Publication<String> first = runtime.publish(dep, "one").publication();
        List<Service> beans = new CopyOnWriteArrayList<>();

        var depHandle = Beans.fixed(dep);
        BeanDefinition<Service> definition = Beans.component("rebind-consumer")
                .with(depHandle)
                .create(deps -> {
                    Service bean = new Service(deps.get(depHandle));
                    beans.add(bean);
                    return bean;
                })
                .provide(out)
                .build();
        MountHandle handle = definition.mount(runtime);

        assertEquals(ComponentState.ACTIVE, settle(handle));
        first.unpublish().awaitSettled(WAIT);
        assertEquals(ComponentState.WAITING, settle(handle));
        runtime.publish(dep, "two").awaitSettled(WAIT);
        assertEquals(ComponentState.ACTIVE, settle(handle));

        assertEquals(2, beans.size());
        assertEquals("one", beans.getFirst().value);
        assertEquals("two", beans.getLast().value);
        assertTrue(beans.getFirst().closed, "old activation bean must be closed on rebind");
        assertFalse(beans.getLast().closed);
    }

    @Test
    void optionalDependencyAppearanceAndDisappearanceReactivateBean() throws Exception {
        List<String> observed = new CopyOnWriteArrayList<>();
        var optHandle = Beans.fixedOptional(OPT);
        BeanDefinition<String> definition = Beans.component("opt-consumer")
                .with(optHandle)
                .create(deps -> {
                    String result = deps.get(optHandle).map(item -> "present:" + item).orElse("empty");
                    observed.add(result);
                    return result;
                })
                .build();
        MountHandle handle = Beans.mount(runtime, definition);
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertEquals(List.of("empty"), observed);

        PublicationChange<String> registration = runtime.publish(OPT, "x");
        registration.awaitSettled(WAIT);
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertEquals(List.of("empty", "present:x"), observed);

        registration.publication().unpublish().awaitSettled(WAIT);
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertEquals(List.of("empty", "present:x", "empty"), observed);
    }

    @Test
    void configReconfigureCreatesFreshBeanWithNewConfig() throws Exception {
        CapabilityKey<Service> out = CapabilityKey.of("cfg-service", Service.class);
        List<Service> beans = new CopyOnWriteArrayList<>();
        ConfiguredBeanDefinition<Prefix, Service> definition =
                Beans.component("cfg-bean", Prefix.class)
                        .create(config -> {
                            Service bean = new Service(config.value());
                            beans.add(bean);
                            return bean;
                        })
                        .provide(out)
                        .build();

        ConfiguredMountHandle<Prefix> handle = definition.mount(runtime, new Prefix("one"));
        assertEquals(ComponentState.ACTIVE, settle(handle));
        long oldRevision = handle.configRevision();

        assertEquals(ComponentState.ACTIVE, handle.reconfigureAsync(new Prefix("two"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        assertEquals(2, beans.size());
        assertEquals("one", beans.getFirst().value);
        assertEquals("two", beans.getLast().value);
        assertTrue(beans.getFirst().closed);
        assertFalse(beans.getLast().closed);
        assertTrue(handle.configRevision() > oldRevision);
    }

    @Test
    void autoLifecyclePrefersAsyncCloseableOverAutoCloseable() throws Exception {
        AsyncBean bean = new AsyncBean();
        BeanDefinition<AsyncBean> definition = Beans.component("async-auto")
                .create(() -> bean)
                .build();
        MountHandle handle = Beans.mount(runtime, definition);
        assertEquals(ComponentState.ACTIVE, settle(handle));

        assertEquals(ComponentState.DISPOSED, handle.disposeAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(bean.asyncClosed);
        assertFalse(bean.syncClosed);
    }

    @Test
    void autoLifecycleManagesPlainAutoCloseable() throws Exception {
        Service bean = new Service("x");
        BeanDefinition<Service> definition = Beans.component("sync-auto")
                .create(() -> bean)
                .build();
        MountHandle handle = Beans.mount(runtime, definition);
        assertEquals(ComponentState.ACTIVE, settle(handle));

        assertEquals(ComponentState.DISPOSED, handle.disposeAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(bean.closed);
    }

    @Test
    void unmanagedBeanIsNotClosed() throws Exception {
        Service bean = new Service("x");
        BeanDefinition<Service> definition = Beans.component("unmanaged-bean")
                .create(() -> bean)
                .unmanaged()
                .build();
        MountHandle handle = Beans.mount(runtime, definition);
        assertEquals(ComponentState.ACTIVE, settle(handle));

        assertEquals(ComponentState.DISPOSED, handle.disposeAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertFalse(bean.closed);
    }

    @Test
    void customAsyncDisposerBlocksSettlementUntilStageCompletes() throws Exception {
        Service bean = new Service("x");
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        BeanDefinition<Service> definition = Beans.component("gated-dispose")
                .create(() -> bean)
                .destroyAsyncWith(item -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    return gate;
                })
                .build();
        MountHandle handle = Beans.mount(runtime, definition);
        assertEquals(ComponentState.ACTIVE, settle(handle));

        var disposing = handle.disposeAsync().toCompletableFuture();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertFalse(disposing.isDone());

        gate.complete(null);
        assertEquals(ComponentState.DISPOSED, disposing.get(10, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        assertFalse(bean.closed, "custom disposer replaces AUTO close inference");
    }

    @Test
    void failedCleanupCanBeRetried() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        BeanDefinition<Service> definition = Beans.component("retry-cleanup")
                .create(() -> new Service("x"))
                .destroyWith(bean -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("temporary");
                    }
                })
                .build();
        MountHandle handle = Beans.mount(runtime, definition);
        assertEquals(ComponentState.ACTIVE, settle(handle));

        assertEquals(ComponentState.FAILED, handle.disposeAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ComponentState.DISPOSED, handle.retryAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
    }

    @Test
    void startFailureRollsBackBeanAndStagedOutputs() throws Exception {
        CapabilityKey<Service> out = CapabilityKey.of("rollback-out", Service.class);
        List<String> readerValues = new CopyOnWriteArrayList<>();
        var outHandle = Beans.fixed(out);
        BeanDefinition<String> reader = Beans.component("rollback-reader")
                .with(outHandle)
                .create(deps -> {
                    readerValues.add(deps.get(outHandle).value);
                    return deps.get(outHandle).value;
                })
                .build();
        MountHandle readerHandle = Beans.mount(runtime, reader);
        assertEquals(ComponentState.WAITING, settle(readerHandle));

        Service[] created = new Service[1];
        BeanDefinition<Service> definition = Beans.component("rollback-provider")
                .create(() -> created[0] = new Service("x"))
                .initializer(bean -> {
                    throw new IllegalStateException("init failed");
                })
                .provide(out)
                .build();
        MountHandle handle = Beans.mount(runtime, definition);

        assertEquals(ComponentState.FAILED, settle(handle));
        assertNotNull(created[0]);
        assertTrue(created[0].closed, "cleanup must be registered before initializer runs");
        assertEquals(ComponentState.WAITING, settle(readerHandle));
        assertTrue(readerValues.isEmpty());
        assertTrue(runtime.advanced().snapshot().diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.ACTIVATION_FAILED
                        && diagnostic.message().contains("init failed")));
    }

    @Test
    void multipleOutputsCommitAtomically() throws Exception {
        CapabilityKey<Service> primary = CapabilityKey.of("atomic-primary", Service.class);
        CapabilityKey<Integer> derived = CapabilityKey.of("atomic-derived", Integer.class);
        List<String> readerValues = new CopyOnWriteArrayList<>();
        var primaryHandle = Beans.fixed(primary);
        BeanDefinition<String> reader = Beans.component("atomic-reader")
                .with(primaryHandle)
                .create(deps -> {
                    readerValues.add(deps.get(primaryHandle).value);
                    return deps.get(primaryHandle).value;
                })
                .build();
        MountHandle readerHandle = Beans.mount(runtime, reader);
        assertEquals(ComponentState.WAITING, settle(readerHandle));

        Service[] created = new Service[1];
        BeanDefinition<Service> broken = Beans.component("broken-outputs")
                .create(() -> created[0] = new Service("x"))
                .provide(primary)
                .provideAs(derived, bean -> null)
                .build();
        MountHandle brokenHandle = Beans.mount(runtime, broken);
        assertEquals(ComponentState.FAILED, settle(brokenHandle));
        assertTrue(created[0].closed);
        assertEquals(ComponentState.WAITING, settle(readerHandle));
        assertTrue(readerValues.isEmpty(), "no output may be visible when another output fails");

        BeanDefinition<Service> good = Beans.component("good-outputs")
                .create(() -> new Service("ok"))
                .provide(primary)
                .provideAs(derived, bean -> bean.value.length())
                .build();
        MountHandle goodHandle = Beans.mount(runtime, good);
        assertEquals(ComponentState.ACTIVE, settle(goodHandle));
        assertEquals(ComponentState.ACTIVE, settle(readerHandle));
        assertEquals(List.of("ok"), readerValues);
    }

    @Test
    void duplicateDependencyOutputAndCrossNamesAreRejectedAtBuild() {
        CapabilityKey<String> first = CapabilityKey.of("dup-out", String.class);
        CapabilityKey<Integer> second = CapabilityKey.of("dup-out", Integer.class);
        Beans.OutputStage<String> outputStage = Beans.component("dup-component")
                .create(() -> "x")
                .provide(first);

        IllegalArgumentException outputError = assertThrows(IllegalArgumentException.class,
                () -> outputStage.provideAs(second, value -> 1).build());
        assertTrue(outputError.getMessage().contains("duplicate output name 'dup-out'"));
        assertTrue(outputError.getMessage().contains("component dup-component"));

        var d0Fixed = Beans.fixed(D0);
        var d0Opt = Beans.fixedOptional(D0);
        IllegalArgumentException dependencyError = assertThrows(IllegalArgumentException.class,
                () -> Beans.component("dup-dependency")
                        .with(d0Fixed, d0Opt)
                        .create(deps -> deps.get(d0Fixed))
                        .build());
        assertTrue(dependencyError.getMessage().contains("duplicate dependency name 'beans-d0'"));
        assertTrue(dependencyError.getMessage().contains("component dup-dependency"));

        CapabilityKey<Object> sameAsDependency = CapabilityKey.of("beans-d0", Object.class);
        IllegalArgumentException crossError = assertThrows(IllegalArgumentException.class,
                () -> Beans.component("cross-name")
                        .with(d0Fixed)
                        .create(deps -> deps.get(d0Fixed))
                        .provideAs(sameAsDependency, value -> value)
                        .build());
        assertTrue(crossError.getMessage().contains("dependency and output names conflict"));
        assertTrue(crossError.getMessage().contains("beans-d0"));
        assertTrue(crossError.getMessage().contains("component cross-name"));

        IllegalArgumentException expertDependencies = assertThrows(
                IllegalArgumentException.class,
                () -> Beans.expert(
                        "expert-duplicates",
                        List.of(Beans.fixed(D0), Beans.fixedOptional(D0)),
                        context -> "x")
                        .build());
        assertTrue(expertDependencies.getMessage().contains("duplicate dependency name"));
        assertTrue(expertDependencies.getMessage().contains("component expert-duplicates"));

        IllegalArgumentException expertCross = assertThrows(
                IllegalArgumentException.class,
                () -> Beans.expert(
                        "expert-cross",
                        List.of(Beans.fixed(D0)),
                        context -> "x")
                        .provideAs(sameAsDependency, value -> value)
                        .build());
        assertTrue(expertCross.getMessage().contains("dependency and output names conflict"));
        assertTrue(expertCross.getMessage().contains("component expert-cross"));

        IllegalArgumentException configuredExpertCross = assertThrows(
                IllegalArgumentException.class,
                () -> Beans.expert(
                        "expert-config-cross",
                        Prefix.class,
                        List.of(Beans.fixed(D0)),
                        (context, config) -> "x")
                        .provideAs(sameAsDependency, value -> value)
                        .build());
        assertTrue(configuredExpertCross.getMessage().contains("component expert-config-cross"));
    }

    @Test
    void singleConfigurationMethodsRejectSilentOverwrite() {
        Beans.OutputStage<String> initialized = Beans.component("overwrite-init")
                .create(() -> "x")
                .initializer(value -> { });
        assertThrows(IllegalStateException.class,
                () -> initialized.initializer(value -> { }));

        Beans.ConfigOutputStage<Prefix, String> normalized =
                Beans.component("overwrite-normalizer", Prefix.class)
                        .create(config -> config.value())
                        .normalizeConfig(config -> config);
        assertThrows(IllegalStateException.class,
                () -> normalized.normalizeConfig(config -> config));

        Beans.OutputStage<String> destroyed = Beans.component("overwrite-disposal")
                .create(() -> "x")
                .destroyWith(value -> { });
        assertThrows(IllegalStateException.class, destroyed::unmanaged);
        assertThrows(IllegalStateException.class, () -> destroyed.destroyAsyncWith(value -> null));
    }

    @Test
    void factoryAndComponentIdsAreExplicitAndStable() {
        BeanDefinition<String> first = Beans.component("stable-id")
                .create(() -> "x")
                .build();
        BeanDefinition<String> second = Beans.component("stable-id")
                .create(() -> "y")
                .build();

        assertEquals("stable-id", first.factoryId());
        assertEquals("stable-id", first.componentId());
        assertEquals("stable-id", first.descriptor().componentId());
        MountFactory factory = first.asFactory();
        assertEquals("stable-id", factory.factoryId());
        assertEquals("stable-id", factory.create().descriptor().componentId());
        assertEquals("stable-id", second.asFactory().create().descriptor().componentId());
    }

    @Test
    void mountConveniencesDefaultToComponentIdAndSupportExplicitMountId() throws Exception {
        BeanDefinition<Service> noConfig = Beans.component("mount-default")
                .create(() -> new Service("x"))
                .build();
        MountHandle defaultNoConfig = noConfig.mount(runtime);
        assertEquals("mount-default", defaultNoConfig.mountId());
        assertFalse(defaultNoConfig instanceof ConfiguredMountHandle<?>);
        assertEquals(ComponentState.ACTIVE, settle(defaultNoConfig));

        MountHandle explicitNoConfig = Beans.mount(runtime, noConfig, "mount-explicit");
        assertEquals("mount-explicit", explicitNoConfig.mountId());
        assertEquals(ComponentState.ACTIVE, settle(explicitNoConfig));

        ConfiguredBeanDefinition<Prefix, Service> configured =
                Beans.component("mount-configured", Prefix.class)
                        .create(config -> new Service(config.value()))
                        .build();
        ConfiguredMountHandle<Prefix> defaultConfigured =
                configured.mount(runtime, new Prefix("one"));
        assertEquals("mount-configured", defaultConfigured.mountId());
        assertEquals(ComponentState.ACTIVE, settle(defaultConfigured));

        ConfiguredMountHandle<Prefix> explicitConfigured =
                Beans.mount(runtime, configured, "mount-configured-explicit", new Prefix("two"));
        assertEquals("mount-configured-explicit", explicitConfigured.mountId());
        assertEquals(ComponentState.ACTIVE, settle(explicitConfigured));
    }

    @Test
    void noConfigCreatorResolvesArbitraryNumberOfDependenciesInOrder() throws Exception {
        register(D0, "0");
        register(D1, "1");
        register(D2, "2");
        register(D3, "3");
        register(D4, "4");
        register(D5, "5");
        register(D6, "6");
        List<String> joined = new CopyOnWriteArrayList<>();

        var d0 = Beans.fixed(D0);
        var d1 = Beans.fixed(D1);
        var d2 = Beans.fixed(D2);
        var d3 = Beans.fixed(D3);
        var d4 = Beans.fixed(D4);
        var d5 = Beans.fixed(D5);
        var d6 = Beans.fixed(D6);

        assertEquals(ComponentState.ACTIVE, settle(Beans.mount(runtime, Beans.component("arity-0")
                .create(() -> {
                    joined.add("");
                    return "";
                })
                .build())));

        assertEquals(ComponentState.ACTIVE, settle(Beans.mount(runtime, Beans.component("arity-1")
                .with(d0)
                .create(deps -> {
                    String v = deps.get(d0);
                    joined.add(v);
                    return v;
                })
                .build())));

        assertEquals(ComponentState.ACTIVE, settle(Beans.mount(runtime, Beans.component("arity-2")
                .with(d0, d1)
                .create(deps -> {
                    String value = deps.get(d0) + deps.get(d1);
                    joined.add(value);
                    return value;
                })
                .build())));

        assertEquals(ComponentState.ACTIVE, settle(Beans.mount(runtime, Beans.component("arity-3")
                .with(d0, d1, d2)
                .create(deps -> {
                    String value = deps.get(d0) + deps.get(d1) + deps.get(d2);
                    joined.add(value);
                    return value;
                })
                .build())));

        assertEquals(ComponentState.ACTIVE, settle(Beans.mount(runtime, Beans.component("arity-4")
                .with(d0, d1, d2, d3)
                .create(deps -> {
                    String value = deps.get(d0) + deps.get(d1) + deps.get(d2) + deps.get(d3);
                    joined.add(value);
                    return value;
                })
                .build())));

        assertEquals(ComponentState.ACTIVE, settle(Beans.mount(runtime, Beans.component("arity-5")
                .with(d0, d1, d2, d3, d4)
                .create(deps -> {
                    String value = deps.get(d0) + deps.get(d1) + deps.get(d2) + deps.get(d3) + deps.get(d4);
                    joined.add(value);
                    return value;
                })
                .build())));

        // Support beyond 5 dependencies natively!
        assertEquals(ComponentState.ACTIVE, settle(Beans.mount(runtime, Beans.component("arity-7")
                .with(d0, d1, d2, d3, d4, d5, d6)
                .create(deps -> {
                    String value = deps.get(d0) + deps.get(d1) + deps.get(d2) + deps.get(d3)
                            + deps.get(d4) + deps.get(d5) + deps.get(d6);
                    joined.add(value);
                    return value;
                })
                .build())));

        assertEquals(List.of("", "0", "01", "012", "0123", "01234", "0123456"), joined);
    }

    @Test
    void configuredCreatorResolvesArbitraryNumberOfDependenciesWithConfig() throws Exception {
        register(D0, "0");
        register(D1, "1");
        register(D2, "2");
        register(D3, "3");
        register(D4, "4");
        register(D5, "5");
        List<String> joined = new CopyOnWriteArrayList<>();

        var d0 = Beans.fixed(D0);
        var d1 = Beans.fixed(D1);
        var d2 = Beans.fixed(D2);
        var d3 = Beans.fixed(D3);
        var d4 = Beans.fixed(D4);
        var d5 = Beans.fixed(D5);

        assertEquals(ComponentState.ACTIVE, settle(Beans.mount(runtime,
                Beans.component("cfg-arity-0", Prefix.class)
                        .create(config -> {
                            joined.add(config.value());
                            return config.value();
                        })
                        .build(), new Prefix("C"))));

        assertEquals(ComponentState.ACTIVE, settle(Beans.mount(runtime,
                Beans.component("cfg-arity-1", Prefix.class)
                        .with(d0)
                        .create((config, deps) -> {
                            String value = config.value() + deps.get(d0);
                            joined.add(value);
                            return value;
                        })
                        .build(), new Prefix("C"))));

        assertEquals(ComponentState.ACTIVE, settle(Beans.mount(runtime,
                Beans.component("cfg-arity-2", Prefix.class)
                        .with(d0, d1)
                        .create((config, deps) -> {
                            String value = config.value() + deps.get(d0) + deps.get(d1);
                            joined.add(value);
                            return value;
                        })
                        .build(), new Prefix("C"))));

        assertEquals(ComponentState.ACTIVE, settle(Beans.mount(runtime,
                Beans.component("cfg-arity-6", Prefix.class)
                        .with(d0, d1, d2, d3, d4, d5)
                        .create((config, deps) -> {
                            String value = config.value() + deps.get(d0) + deps.get(d1)
                                    + deps.get(d2) + deps.get(d3) + deps.get(d4) + deps.get(d5);
                            joined.add(value);
                            return value;
                        })
                        .build(), new Prefix("C"))));

        assertEquals(List.of("C", "C0", "C01", "C012345"), joined);
    }

    @Test
    void expertApiUsesDependencyListAndActivationContextCreator() throws Exception {
        register(D0, "0");
        List<String> observed = new CopyOnWriteArrayList<>();
        List<BeanDependency<?>> dependencies = List.of(
                Beans.fixed(D0),
                Beans.fixedOptional(OPT));
        BeanDefinition<String> definition = Beans.expert(
                        "expert-bean",
                        dependencies,
                        context -> {
                            String value = context.require(D0)
                                    + context.find(OPT).map(item -> "+" + item).orElse("");
                            observed.add(value);
                            return value;
                        })
                .provide(CapabilityKey.of("expert-out", String.class))
                .build();

        assertEquals(List.of("beans-d0", "beans-opt"),
                definition.dependencies().stream().map(BeanDependency::name).toList());
        assertEquals(Mode.REQUIRED,
                definition.descriptor().requirement(D0).orElseThrow().mode());
        assertEquals(Mode.OPTIONAL,
                definition.descriptor().requirement(OPT).orElseThrow().mode());

        MountHandle handle = definition.mount(runtime);
        assertEquals(ComponentState.ACTIVE, settle(handle));
        runtime.publish(OPT, "x").awaitSettled(WAIT);
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertEquals(List.of("0", "0+x"), observed);
    }

    @Test
    void configNormalizerRunsBeforeCreatorAndRejectsNull() throws Exception {
        List<String> observed = new CopyOnWriteArrayList<>();
        ConfiguredBeanDefinition<Prefix, String> definition =
                Beans.component("normalized", Prefix.class)
                        .create(config -> {
                            observed.add(config.value());
                            return config.value();
                        })
                        .normalizeConfig(config -> new Prefix(config.value().trim()))
                        .build();
        ConfiguredMountHandle<Prefix> handle =
                definition.mount(runtime, new Prefix("  padded  "));
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertEquals(List.of("padded"), observed);

        ConfiguredBeanDefinition<Prefix, String> invalid =
                Beans.component("invalid-normalizer", Prefix.class)
                        .create(config -> config.value())
                        .normalizeConfig(config -> null)
                        .build();
        TransactionRejectedException rejected = assertRejected(DiagnosticCode.INVALID_CONFIG,
                () -> invalid.mount(runtime, new Prefix("x")));
        assertTrue(rejected.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.code() == DiagnosticCode.INVALID_CONFIG
                                && diagnostic.message().contains("config normalizer returned null")),
                () -> rejected.diagnostics().toString());
    }

    @Test
    void nullBeanFailsActivationWithClearDiagnostic() throws Exception {
        BeanDefinition<String> definition = Beans.component("null-bean")
                .<String>create(() -> null)
                .build();
        MountHandle handle = definition.mount(runtime);
        assertEquals(ComponentState.FAILED, settle(handle));
        assertTrue(runtime.advanced().snapshot().diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.ACTIVATION_FAILED
                        && diagnostic.message().contains("bean creator returned null")));
    }

    @Test
    void checkedCreatorExceptionFailsActivation() throws Exception {
        BeanDefinition<String> definition = Beans.component("checked-creator")
                .<String>create(() -> {
                    throw new IOException("creator failed");
                })
                .build();
        MountHandle handle = definition.mount(runtime);
        assertEquals(ComponentState.FAILED, settle(handle));
        assertTrue(runtime.advanced().snapshot().diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.ACTIVATION_FAILED
                        && diagnostic.message().contains("creator failed")));
    }

    @Test
    void configTypeIsCarriedOnlyByConfiguredDefinition() {
        ConfiguredBeanDefinition<Prefix, String> configured =
                Beans.component("cfg-spec", Prefix.class)
                        .create(config -> config.value())
                        .build();
        assertEquals(Prefix.class, configured.configType());

        BeanDefinition<String> noConfig = Beans.component("no-config-spec")
                .create(() -> "x")
                .build();
        assertEquals(BeanDefinition.class, noConfig.getClass());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rawFactoryCallWithWrongConfigTypeIsRejectedAsInvalidConfig() {
        ConfiguredBeanDefinition<Prefix, String> definition =
                Beans.component("raw-factory", Prefix.class)
                        .create(config -> config.value())
                        .build();
        ComponentFactory raw = (ComponentFactory) definition.asFactory();

        TransactionRejectedException rejected = assertRejected(DiagnosticCode.INVALID_CONFIG,
                () -> runtime.mount("raw-factory", raw, new Object()));
        assertTrue(rejected.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.code() == DiagnosticCode.INVALID_CONFIG
                                && diagnostic.message().contains("config type mismatch")
                                && diagnostic.message().contains("expected " + Prefix.class.getName())
                                && diagnostic.message().contains("got java.lang.Object")),
                () -> rejected.diagnostics().toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dynamicInjectsLeaseProxyAndDoesNotRestartOnProviderChange() throws Exception {
        CapabilityKey<Api> api = CapabilityKey.of("beans-dynamic-api", Api.class);
        CapabilityKey<Supplier<String>> output = CapabilityKey.of(
                "beans-dynamic-output", (Class<Supplier<String>>) (Class<?>) Supplier.class);
        AtomicInteger starts = new AtomicInteger();

        var apiDep = Beans.dynamic(api);
        BeanDefinition<Supplier<String>> definition = Beans.component("beans-dynamic-consumer")
                .with(apiDep)
                .<Supplier<String>>create(deps -> {
                    starts.incrementAndGet();
                    Api proxy = deps.get(apiDep);
                    return () -> proxy.value();
                })
                .provide(output)
                .build();

        MountHandle handle = definition.mount(runtime);
        assertEquals(ComponentState.WAITING, settle(handle));
        PublicationChange<Api> first = runtime.publish(api, new ApiValue("v1"));
        first.awaitSettled(WAIT);
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertEquals("v1", runtime.root().view().require(output).get());

        first.publication().unpublish().awaitSettled(WAIT);
        runtime.publish(api, new ApiValue("v2")).awaitSettled(WAIT);
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertEquals(1, starts.get(), "dynamic provider replacement must not recreate the bean");
        assertEquals("v2", runtime.root().view().require(output).get());
    }

    @Test
    void dynamicDeclarationsExposeRequiredAndOptionalProxyModes() {
        CapabilityKey<Api> required = CapabilityKey.of("beans-proxy-required", Api.class);
        CapabilityKey<Api> optional = CapabilityKey.of("beans-proxy-optional", Api.class);

        BeanDependency<Api> requiredDependency = Beans.dynamic(required);
        assertEquals(CapabilityBinding.DYNAMIC, requiredDependency.requirement().binding());
        assertEquals(Mode.REQUIRED, requiredDependency.requirement().mode());

        BeanDependency<Api> optionalDependency = Beans.dynamicOptional(optional);
        assertEquals(CapabilityBinding.DYNAMIC, optionalDependency.requirement().binding());
        assertEquals(Mode.OPTIONAL, optionalDependency.requirement().mode());
    }

    @Test
    void dynamicRejectsNonInterfaceCapabilityAtDeclaration() {
        CapabilityKey<ApiValue> key = CapabilityKey.of("beans-proxy-class-required", ApiValue.class);
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class, () -> Beans.dynamic(key));
        assertTrue(rejected.getMessage().contains("must be an interface"));
        assertTrue(rejected.getMessage().contains(ApiValue.class.getName()));
    }

    @Test
    void dynamicOptionalRejectsNonInterfaceCapabilityAtDeclaration() {
        CapabilityKey<ApiValue> key = CapabilityKey.of("beans-proxy-class-optional", ApiValue.class);
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class, () -> Beans.dynamicOptional(key));
        assertTrue(rejected.getMessage().contains("must be an interface"));
        assertTrue(rejected.getMessage().contains(ApiValue.class.getName()));
    }

    @Test
    void dynamicCapabilityFactoriesExposeExplicitLeasedCallEntrypoint() throws Exception {
        CapabilityKey<Api> api = CapabilityKey.of("beans-explicit-dynamic", Api.class);
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();

        BeanDependency<DynamicCapability<Api>> dependency = Beans.dynamicCapabilityOptional(api);
        assertEquals(CapabilityBinding.DYNAMIC, dependency.requirement().binding());
        assertEquals(Mode.OPTIONAL, dependency.requirement().mode());

        BeanDefinition<Boolean> definition = Beans.component("beans-explicit-consumer")
                .with(dependency)
                .<Boolean>create(deps -> {
                    DynamicCapability<Api> capability = deps.get(dependency);
                    dynamic.set(capability);
                    return capability.available();
                })
                .build();
        MountHandle handle = definition.mount(runtime);
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertFalse(dynamic.get().available());

        runtime.publish(api, new ApiValue("v1")).awaitSettled(WAIT);
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertTrue(dynamic.get().available());
        assertEquals("v1", dynamic.get().call(Api::value));
    }

    @Test
    void outputKeysAreReadOnlyAndPreserveDeclarationOrder() {
        CapabilityKey<String> first = CapabilityKey.of("beans-key-first", String.class);
        CapabilityKey<Object> second = CapabilityKey.of("beans-key-second", Object.class);
        BeanDefinition<Service> definition = Beans.component("beans-keys")
                .create(() -> new Service("x"))
                .provideAs(first, bean -> bean.value)
                .provideAs(second, bean -> bean.value)
                .build();

        assertEquals(List.of(first, second), definition.outputKeys());
        assertThrows(UnsupportedOperationException.class, () -> definition.outputKeys().clear());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void normalizerReturningWrongTypeIsRejectedAsInvalidConfig() {
        Beans.Normalizer invalid = config -> new Object();
        ConfiguredBeanDefinition<Prefix, String> definition =
                (ConfiguredBeanDefinition) Beans.component("beans-bad-normalizer", Prefix.class)
                        .create(config -> config.value())
                        .normalizeConfig(invalid)
                        .build();

        TransactionRejectedException rejected = assertRejected(DiagnosticCode.INVALID_CONFIG,
                () -> definition.mount(runtime, new Prefix("x")));
        assertTrue(rejected.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.message().contains("invalid config type")),
                () -> rejected.diagnostics().toString());
    }

    @Test
    void classShortcutsUseContractBinaryNameForDependenciesAndOutputs() throws Exception {
        var apiDep = Beans.fixedOptional(Api.class);
        BeanDefinition<String> definition = Beans.component("class-shortcuts")
                .with(apiDep)
                .create(deps -> deps.get(apiDep).map(Api::value).orElse("none"))
                .provide(String.class)
                .build();
        assertEquals(String.class.getName(), definition.outputKeys().getFirst().name());

        MountHandle handle = definition.mount(runtime);
        assertEquals(ComponentState.ACTIVE, settle(handle));
        runtime.publish(Api.class, new ApiValue("x")).awaitSettled(WAIT);
        assertEquals(ComponentState.ACTIVE, settle(handle));
    }

    @Test
    void publicSurfaceHasNoTopLevelArityFamilyAndHidesNoConfigFromSimplePath() throws Exception {
        Path sourceRoot = Path.of("src/main/java/io/knotra/beans");
        Pattern publicType = Pattern.compile(
                "^public\\s+(?:final\\s+)?(?:class|interface|record|enum)\\s+(\\w+)");
        List<String> publicTypes;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            publicTypes = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> {
                        try {
                            return Files.readAllLines(path).stream()
                                    .map(publicType::matcher)
                                    .filter(Matcher::find)
                                    .map(matcher -> matcher.group(1));
                        } catch (IOException error) {
                            throw new RuntimeException(error);
                        }
                    })
                    .toList();
        }
        assertEquals(
                List.of("BeanDependencies", "ConfiguredBeanDefinition", "BeanDefinition", "Beans", "BeanDependency"),
                publicTypes);
        assertEquals(MountFactory.class,
                BeanDefinition.class.getDeclaredMethod("asFactory").getReturnType());
        assertEquals(ComponentFactory.class,
                ConfiguredBeanDefinition.class.getDeclaredMethod("asFactory").getReturnType());

        String quickStart = """
                package demo;
                import io.knotra.KnotraRuntime;
                import io.knotra.beans.Beans;
                class QuickStart {
                    static void run(KnotraRuntime runtime) {
                        Beans.component("quick-start").create(() -> "ok").build().mount(runtime);
                    }
                }
                """;
        assertFalse(quickStart.contains("NoConfig"));
    }

    @Test
    void builderSupportsChainingVarargsAndCollections() {
        var d0 = Beans.fixed(D0);
        var d1 = Beans.fixed(D1);
        var d2 = Beans.fixed(D2);

        BeanDefinition<String> chained = Beans.component("chained")
                .with(d0)
                .with(d1)
                .with(d2)
                .create(deps -> "ok")
                .build();
        assertEquals(3, chained.dependencies().size());

        BeanDefinition<String> varargs = Beans.component("varargs")
                .with(d0, d1, d2)
                .create(deps -> "ok")
                .build();
        assertEquals(3, varargs.dependencies().size());

        BeanDefinition<String> collection = Beans.component("collection")
                .with(List.of(d0, d1, d2))
                .create(deps -> "ok")
                .build();
        assertEquals(3, collection.dependencies().size());

        ConfiguredBeanDefinition<Prefix, String> cfgVarargs = Beans.component("cfg-varargs", Prefix.class)
                .with(d0, d1, d2)
                .create((config, deps) -> config.value())
                .build();
        assertEquals(3, cfgVarargs.dependencies().size());
    }

    @Test
    void undeclaredDependencyResolutionThrowsIllegalArgumentException() throws Exception {
        register(D0, "0");
        var declared = Beans.fixed(D0);
        var undeclared = Beans.fixed(D1);

        BeanDefinition<String> definition = Beans.component("undeclared-consumer")
                .with(declared)
                .create(deps -> deps.get(undeclared))
                .build();

        MountHandle handle = definition.mount(runtime);
        assertEquals(ComponentState.FAILED, settle(handle));
        assertTrue(runtime.advanced().snapshot().diagnostics().stream().anyMatch(d ->
                d.code() == DiagnosticCode.ACTIVATION_FAILED
                        && d.message().contains("dependency was not declared in .with(...) for component 'undeclared-consumer'")
                        && d.message().contains("beans-d1")));
    }

    @Test
    void differentDependencyInstanceWithSameKeyIsRejectedIfUndeclared() throws Exception {
        register(D0, "0");
        var declared = Beans.fixed(D0);
        var undeclaredSameKey = Beans.fixed(D0);

        BeanDefinition<String> definition = Beans.component("same-key-diff-instance")
                .with(declared)
                .create(deps -> deps.get(undeclaredSameKey))
                .build();

        MountHandle handle = definition.mount(runtime);
        assertEquals(ComponentState.FAILED, settle(handle));
        assertTrue(runtime.advanced().snapshot().diagnostics().stream().anyMatch(d ->
                d.code() == DiagnosticCode.ACTIVATION_FAILED
                        && d.message().contains("dependency was not declared in .with(...) for component 'same-key-diff-instance'")));
    }

    @Test
    void happyPathDslAllowsDirectMountAndProvideAsClassAndRuntimeRequire() throws Exception {
        interface Greeting {
            String message();
        }
        record ConstantGreeting(String message) implements Greeting {}
        interface RenderedGreeting {
            String render(String name);
        }
        record GreetingRenderer(Greeting greeting) implements RenderedGreeting {
            @Override
            public String render(String name) {
                return greeting.message() + " " + name;
            }
        }

        var greeting = runtime
                .publish(Greeting.class, new ConstantGreeting("Hello"))
                .publication();

        var greetingDep = Beans.fixed(Greeting.class);
        MountHandle renderer = Beans
                .component("greeting-renderer")
                .with(greetingDep)
                .create(deps -> new GreetingRenderer(deps.get(greetingDep)))
                .provideAs(RenderedGreeting.class)
                .mount(runtime);

        renderer.requireActive(WAIT);

        assertEquals("Hello Knotra",
                runtime.root().view().require(RenderedGreeting.class).render("Knotra"));

        greeting.update(new ConstantGreeting("Hi"))
                .awaitSettled(WAIT);

        assertEquals("Hi Knotra",
                runtime.root().view().require(RenderedGreeting.class).render("Knotra"));
    }

    private ComponentState settle(MountHandle handle) throws Exception {
        return handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static TransactionRejectedException assertRejected(
            DiagnosticCode code,
            Executable action) {
        TransactionRejectedException rejected = assertThrows(
                TransactionRejectedException.class, action);
        assertTrue(rejected.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code() == code),
                () -> rejected.diagnostics().toString());
        return rejected;
    }

    private void register(CapabilityKey<String> key, String value) {
        runtime.publish(key, value).awaitSettled(WAIT);
    }

    record Prefix(String value) {
    }

    interface Api {
        String value();
    }

    record ApiValue(String value) implements Api {
    }

    static final class Service implements AutoCloseable {
        final String value;
        volatile boolean closed;

        Service(String value) {
            this.value = value;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    record OptionalService(java.util.Optional<Service> service) {
    }

    static final class AsyncBean implements AsyncCloseable {
        volatile boolean asyncClosed;
        volatile boolean syncClosed;

        @Override
        public CompletionStage<Void> closeAsync() {
            asyncClosed = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            syncClosed = true;
        }
    }
}
