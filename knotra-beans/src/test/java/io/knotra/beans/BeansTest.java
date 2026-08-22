package io.knotra.beans;

import io.knotra.AsyncCloseable;
import io.knotra.CapabilityKey;
import io.knotra.DynamicCapability;
import io.knotra.CapabilityRequirement.CapabilityBinding;
import io.knotra.CapabilityRequirement.Mode;
import io.knotra.ComponentHandle;
import io.knotra.ComponentFactory;
import io.knotra.ComponentState;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraRuntime;
import io.knotra.NoConfig;
import io.knotra.RegistrationHandle;
import io.knotra.TransactionRejectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    static final CapabilityKey<String> OPT = CapabilityKey.of("beans-opt", String.class);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void providerRebindCreatesFreshBeanAndClosesOldBean() throws Exception {
        CapabilityKey<String> dep = CapabilityKey.of("rebind-dep", String.class);
        CapabilityKey<Service> out = CapabilityKey.of("rebind-service", Service.class);
        RegistrationHandle first = runtime.provide(dep, "one");
        List<Service> beans = new CopyOnWriteArrayList<>();

        BeanDefinition<NoConfig, Service> definition = Beans.component("rebind-consumer")
                .with(Beans.required(dep))
                .create(value -> {
                    Service bean = new Service(value);
                    beans.add(bean);
                    return bean;
                })
                .provide(out)
                .build();
        ComponentHandle<NoConfig> handle = runtime.mount("rebind-consumer", definition);

        assertEquals(ComponentState.ACTIVE, settle(handle));
        runtime.revoke(first);
        assertEquals(ComponentState.WAITING, settle(handle));
        runtime.provide(dep, "two");
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
        BeanDefinition<NoConfig, String> definition = Beans.component("opt-consumer")
                .with(Beans.optional(OPT))
                .create(value -> {
                    String result = value.map(item -> "present:" + item).orElse("empty");
                    observed.add(result);
                    return result;
                })
                .build();
        ComponentHandle<NoConfig> handle = runtime.mount("opt-consumer", definition);
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertEquals(List.of("empty"), observed);

        RegistrationHandle registration = runtime.provide(OPT, "x");
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertEquals(List.of("empty", "present:x"), observed);

        runtime.revoke(registration);
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertEquals(List.of("empty", "present:x", "empty"), observed);
    }

    @Test
    void configReconfigureCreatesFreshBeanWithNewConfig() throws Exception {
        CapabilityKey<Service> out = CapabilityKey.of("cfg-service", Service.class);
        List<Service> beans = new CopyOnWriteArrayList<>();
        BeanDefinition<Prefix, Service> definition = Beans.component("cfg-bean", Prefix.class)
                .create(config -> {
                    Service bean = new Service(config.value());
                    beans.add(bean);
                    return bean;
                })
                .provide(out)
                .build();

        ComponentHandle<Prefix> handle = runtime.mount("cfg-bean", definition, new Prefix("one"));
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
        BeanDefinition<NoConfig, AsyncBean> definition = Beans.component("async-auto")
                .create(() -> bean)
                .build();
        ComponentHandle<NoConfig> handle = runtime.mount("async-auto", definition);
        assertEquals(ComponentState.ACTIVE, settle(handle));

        assertEquals(ComponentState.DISPOSED, handle.disposeAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(bean.asyncClosed);
        assertFalse(bean.syncClosed);
    }

    @Test
    void autoLifecycleManagesPlainAutoCloseable() throws Exception {
        Service bean = new Service("x");
        BeanDefinition<NoConfig, Service> definition = Beans.component("sync-auto")
                .create(() -> bean)
                .build();
        ComponentHandle<NoConfig> handle = runtime.mount("sync-auto", definition);
        assertEquals(ComponentState.ACTIVE, settle(handle));

        assertEquals(ComponentState.DISPOSED, handle.disposeAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(bean.closed);
    }

    @Test
    void unmanagedBeanIsNotClosed() throws Exception {
        Service bean = new Service("x");
        BeanDefinition<NoConfig, Service> definition = Beans.component("unmanaged-bean")
                .create(() -> bean)
                .unmanaged()
                .build();
        ComponentHandle<NoConfig> handle = runtime.mount("unmanaged-bean", definition);
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
        BeanDefinition<NoConfig, Service> definition = Beans.component("gated-dispose")
                .create(() -> bean)
                .destroyAsyncWith(item -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    return gate;
                })
                .build();
        ComponentHandle<NoConfig> handle = runtime.mount("gated-dispose", definition);
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
        BeanDefinition<NoConfig, Service> definition = Beans.component("retry-cleanup")
                .create(() -> new Service("x"))
                .destroyWith(bean -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("temporary");
                    }
                })
                .build();
        ComponentHandle<NoConfig> handle = runtime.mount("retry-cleanup", definition);
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
        BeanDefinition<NoConfig, String> reader = Beans.component("rollback-reader")
                .with(Beans.required(out))
                .create(value -> {
                    readerValues.add(value.value);
                    return value.value;
                })
                .build();
        ComponentHandle<NoConfig> readerHandle = runtime.mount("rollback-reader", reader);
        assertEquals(ComponentState.WAITING, settle(readerHandle));

        Service[] created = new Service[1];
        BeanDefinition<NoConfig, Service> definition = Beans.component("rollback-provider")
                .create(() -> created[0] = new Service("x"))
                .initializer(bean -> {
                    throw new IllegalStateException("init failed");
                })
                .provide(out)
                .build();
        ComponentHandle<NoConfig> handle = runtime.mount("rollback-provider", definition);

        assertEquals(ComponentState.FAILED, settle(handle));
        assertNotNull(created[0]);
        assertTrue(created[0].closed, "cleanup must be registered before initializer runs");
        assertEquals(ComponentState.WAITING, settle(readerHandle));
        assertTrue(readerValues.isEmpty());
        assertTrue(runtime.snapshot().diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.ACTIVATION_FAILED
                        && diagnostic.message().contains("init failed")));
    }

    @Test
    void multipleOutputsCommitAtomically() throws Exception {
        CapabilityKey<Service> primary = CapabilityKey.of("atomic-primary", Service.class);
        CapabilityKey<Integer> derived = CapabilityKey.of("atomic-derived", Integer.class);
        List<String> readerValues = new CopyOnWriteArrayList<>();
        BeanDefinition<NoConfig, String> reader = Beans.component("atomic-reader")
                .with(Beans.required(primary))
                .create(value -> {
                    readerValues.add(value.value);
                    return value.value;
                })
                .build();
        ComponentHandle<NoConfig> readerHandle = runtime.mount("atomic-reader", reader);
        assertEquals(ComponentState.WAITING, settle(readerHandle));

        Service[] created = new Service[1];
        BeanDefinition<NoConfig, Service> broken = Beans.component("broken-outputs")
                .create(() -> created[0] = new Service("x"))
                .provide(primary)
                .provideAs(derived, bean -> null)
                .build();
        ComponentHandle<NoConfig> brokenHandle = runtime.mount("broken-outputs", broken);
        assertEquals(ComponentState.FAILED, settle(brokenHandle));
        assertTrue(created[0].closed);
        assertEquals(ComponentState.WAITING, settle(readerHandle));
        assertTrue(readerValues.isEmpty(), "no output may be visible when another output fails");

        BeanDefinition<NoConfig, Service> good = Beans.component("good-outputs")
                .create(() -> new Service("ok"))
                .provide(primary)
                .provideAs(derived, bean -> bean.value.length())
                .build();
        ComponentHandle<NoConfig> goodHandle = runtime.mount("good-outputs", good);
        assertEquals(ComponentState.ACTIVE, settle(goodHandle));
        assertEquals(ComponentState.ACTIVE, settle(readerHandle));
        assertEquals(List.of("ok"), readerValues);
    }

    @Test
    void duplicateOutputNameIsRejected() {
        CapabilityKey<String> first = CapabilityKey.of("dup-out", String.class);
        CapabilityKey<Integer> second = CapabilityKey.of("dup-out", Integer.class);
        BeanOutputStage<NoConfig, String> stage = Beans.component("dup-component")
                .create(() -> "x")
                .provide(first);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> stage.provideAs(second, value -> 1));
        assertTrue(error.getMessage().contains("duplicate output name: dup-out"));
        assertThrows(IllegalArgumentException.class, () -> stage.provide(first));
    }

    @Test
    void factoryAndComponentIdsAreExplicitAndStable() {
        BeanDefinition<NoConfig, String> first = Beans.component("stable-id")
                .create(() -> "x")
                .build();
        BeanDefinition<NoConfig, String> second = Beans.component("stable-id")
                .create(() -> "y")
                .build();

        assertEquals("stable-id", first.factoryId());
        assertEquals("stable-id", first.componentId());
        assertEquals("stable-id", first.descriptor().componentId());
        assertEquals("stable-id", first.create().descriptor().componentId());
        assertEquals("stable-id", second.create().descriptor().componentId());
    }

    @Test
    void mountConveniencesDefaultToComponentIdAndSupportExplicitMountId() throws Exception {
        BeanDefinition<NoConfig, Service> noConfig = Beans.component("mount-default")
                .create(() -> new Service("x"))
                .build();
        ComponentHandle<NoConfig> defaultNoConfig = Beans.mount(runtime, noConfig);
        assertEquals("mount-default", defaultNoConfig.mountId());
        assertEquals(ComponentState.ACTIVE, settle(defaultNoConfig));

        ComponentHandle<NoConfig> explicitNoConfig =
                Beans.mount(runtime, noConfig, "mount-explicit");
        assertEquals("mount-explicit", explicitNoConfig.mountId());
        assertEquals(ComponentState.ACTIVE, settle(explicitNoConfig));

        BeanDefinition<Prefix, Service> configured = Beans.component("mount-configured", Prefix.class)
                .create(config -> new Service(config.value()))
                .build();
        ComponentHandle<Prefix> defaultConfigured =
                Beans.mount(runtime, configured, new Prefix("one"));
        assertEquals("mount-configured", defaultConfigured.mountId());
        assertEquals(ComponentState.ACTIVE, settle(defaultConfigured));

        ComponentHandle<Prefix> explicitConfigured =
                Beans.mount(runtime, configured, "mount-configured-explicit", new Prefix("two"));
        assertEquals("mount-configured-explicit", explicitConfigured.mountId());
        assertEquals(ComponentState.ACTIVE, settle(explicitConfigured));
    }

    @Test
    void noConfigCreatorAritiesShapesResolveDependenciesInOrder() throws Exception {
        runtime.provide(D0, "0");
        runtime.provide(D1, "1");
        runtime.provide(D2, "2");
        runtime.provide(D3, "3");
        runtime.provide(D4, "4");
        List<String> joined = new CopyOnWriteArrayList<>();

        assertEquals(ComponentState.ACTIVE, settle(runtime.mount("arity-0", Beans.component("arity-0")
                .create(() -> {
                    joined.add("");
                    return "";
                })
                .build())));
        assertEquals(ComponentState.ACTIVE, settle(runtime.mount("arity-1", Beans.component("arity-1")
                .with(Beans.required(D0))
                .create(v1 -> {
                    joined.add(v1);
                    return v1;
                })
                .build())));
        assertEquals(ComponentState.ACTIVE, settle(runtime.mount("arity-2", Beans.component("arity-2")
                .with(Beans.required(D0), Beans.required(D1))
                .create((v1, v2) -> {
                    String value = v1 + v2;
                    joined.add(value);
                    return value;
                })
                .build())));
        assertEquals(ComponentState.ACTIVE, settle(runtime.mount("arity-3", Beans.component("arity-3")
                .with(Beans.required(D0))
                .with(Beans.required(D1), Beans.required(D2))
                .create((v1, v2, v3) -> {
                    String value = v1 + v2 + v3;
                    joined.add(value);
                    return value;
                })
                .build())));
        assertEquals(ComponentState.ACTIVE, settle(runtime.mount("arity-4", Beans.component("arity-4")
                .with(Beans.required(D0), Beans.required(D1))
                .with(Beans.required(D2), Beans.required(D3))
                .create((v1, v2, v3, v4) -> {
                    String value = v1 + v2 + v3 + v4;
                    joined.add(value);
                    return value;
                })
                .build())));
        assertEquals(ComponentState.ACTIVE, settle(runtime.mount("arity-5", Beans.component("arity-5")
                .with(Beans.required(D0), Beans.required(D1), Beans.required(D2))
                .with(Beans.required(D3), Beans.required(D4))
                .create((v1, v2, v3, v4, v5) -> {
                    String value = v1 + v2 + v3 + v4 + v5;
                    joined.add(value);
                    return value;
                })
                .build())));

        assertEquals(List.of("", "0", "01", "012", "0123", "01234"), joined);
    }

    @Test
    void configuredCreatorAritityShapesReceiveConfigFirst() throws Exception {
        runtime.provide(D0, "0");
        runtime.provide(D1, "1");
        runtime.provide(D2, "2");
        runtime.provide(D3, "3");
        runtime.provide(D4, "4");
        List<String> joined = new CopyOnWriteArrayList<>();

        assertEquals(ComponentState.ACTIVE, settle(runtime.mount("cfg-arity-0",
                Beans.component("cfg-arity-0", Prefix.class)
                        .create(config -> {
                            joined.add(config.value());
                            return config.value();
                        })
                        .build(),
                new Prefix("C"))));
        assertEquals(ComponentState.ACTIVE, settle(runtime.mount("cfg-arity-1",
                Beans.component("cfg-arity-1", Prefix.class)
                        .with(Beans.required(D0))
                        .create((config, v1) -> {
                            String value = config.value() + v1;
                            joined.add(value);
                            return value;
                        })
                        .build(),
                new Prefix("C"))));
        assertEquals(ComponentState.ACTIVE, settle(runtime.mount("cfg-arity-2",
                Beans.component("cfg-arity-2", Prefix.class)
                        .with(Beans.required(D0), Beans.required(D1))
                        .create((config, v1, v2) -> {
                            String value = config.value() + v1 + v2;
                            joined.add(value);
                            return value;
                        })
                        .build(),
                new Prefix("C"))));
        assertEquals(ComponentState.ACTIVE, settle(runtime.mount("cfg-arity-3",
                Beans.component("cfg-arity-3", Prefix.class)
                        .with(Beans.required(D0), Beans.required(D1), Beans.required(D2))
                        .create((config, v1, v2, v3) -> {
                            String value = config.value() + v1 + v2 + v3;
                            joined.add(value);
                            return value;
                        })
                        .build(),
                new Prefix("C"))));
        assertEquals(ComponentState.ACTIVE, settle(runtime.mount("cfg-arity-4",
                Beans.component("cfg-arity-4", Prefix.class)
                        .with(Beans.required(D0), Beans.required(D1), Beans.required(D2))
                        .with(Beans.required(D3))
                        .create((config, v1, v2, v3, v4) -> {
                            String value = config.value() + v1 + v2 + v3 + v4;
                            joined.add(value);
                            return value;
                        })
                        .build(),
                new Prefix("C"))));
        assertEquals(ComponentState.ACTIVE, settle(runtime.mount("cfg-arity-5",
                Beans.component("cfg-arity-5", Prefix.class)
                        .with(Beans.required(D0), Beans.required(D1), Beans.required(D2))
                        .with(Beans.required(D3), Beans.required(D4))
                        .create((config, v1, v2, v3, v4, v5) -> {
                            String value = config.value() + v1 + v2 + v3 + v4 + v5;
                            joined.add(value);
                            return value;
                        })
                        .build(),
                new Prefix("C"))));

        assertEquals(List.of("C", "C0", "C01", "C012", "C0123", "C01234"), joined);
    }

    @Test
    void expertApiUsesDependencyListAndActivationContextCreator() throws Exception {
        runtime.provide(D0, "0");
        List<String> observed = new CopyOnWriteArrayList<>();
        List<BeanDependency<?>> dependencies = List.of(
                Beans.required(D0),
                Beans.optional(OPT));
        BeanDefinition<NoConfig, String> definition = BeanDefinition.<NoConfig, String>expert(
                        "expert-bean",
                        NoConfig.class,
                        dependencies,
                        (context, config) -> {
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

        ComponentHandle<NoConfig> handle = runtime.mount("expert-bean", definition);
        assertEquals(ComponentState.ACTIVE, settle(handle));
        runtime.provide(OPT, "x");
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertEquals(List.of("0", "0+x"), observed);
    }

    @Test
    void configNormalizerRunsBeforeCreatorAndRejectsNull() throws Exception {
        List<String> observed = new CopyOnWriteArrayList<>();
        BeanDefinition<Prefix, String> definition = Beans.component("normalized", Prefix.class)
                .create(config -> {
                    observed.add(config.value());
                    return config.value();
                })
                .normalizeConfig(config -> new Prefix(config.value().trim()))
                .build();
        ComponentHandle<Prefix> handle = runtime.mount("normalized", definition, new Prefix("  padded  "));
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertEquals(List.of("padded"), observed);

        BeanDefinition<Prefix, String> invalid = Beans.component("invalid-normalizer", Prefix.class)
                .create(config -> config.value())
                .normalizeConfig(config -> null)
                .build();
        TransactionRejectedException rejected = assertRejected(DiagnosticCode.INVALID_CONFIG,
                () -> runtime.mount("invalid-normalizer", invalid, new Prefix("x")));
        assertTrue(rejected.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.INVALID_CONFIG
                        && diagnostic.message().contains("config normalizer returned null")),
                () -> rejected.diagnostics().toString());
    }

    @Test
    void nullBeanFailsActivationWithClearDiagnostic() throws Exception {
        BeanDefinition<NoConfig, String> definition = Beans.component("null-bean")
                .<String>create(() -> null)
                .build();
        ComponentHandle<NoConfig> handle = runtime.mount("null-bean", definition);
        assertEquals(ComponentState.FAILED, settle(handle));
        assertTrue(runtime.snapshot().diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.ACTIVATION_FAILED
                        && diagnostic.message().contains("bean creator returned null")));
    }

    @Test
    void checkedCreatorExceptionFailsActivation() throws Exception {
        BeanDefinition<NoConfig, String> definition = Beans.component("checked-creator")
                .<String>create(() -> {
                    throw new IOException("creator failed");
                })
                .build();
        ComponentHandle<NoConfig> handle = runtime.mount("checked-creator", definition);
        assertEquals(ComponentState.FAILED, settle(handle));
        assertTrue(runtime.snapshot().diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.ACTIVATION_FAILED
                        && diagnostic.message().contains("creator failed")));
    }

    @Test
    void configTypeIsCarriedAsImmutableSpec() {
        BeanDefinition<Prefix, String> configured = Beans.component("cfg-spec", Prefix.class)
                .create(config -> config.value())
                .build();
        assertEquals(Prefix.class, configured.configType());

        BeanDefinition<NoConfig, String> noConfig = Beans.component("no-config-spec")
                .create(() -> "x")
                .build();
        assertEquals(NoConfig.class, noConfig.configType());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rawFactoryCallWithWrongConfigTypeIsRejectedAsInvalidConfig() {
        BeanDefinition<Prefix, String> definition = Beans.component("raw-factory", Prefix.class)
                .create(config -> config.value())
                .build();
        ComponentFactory raw = (ComponentFactory) definition;

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
    void dynamicProxyRequiredInjectsLeaseProxyAndDoesNotRestartOnProviderChange() throws Exception {
        CapabilityKey<Api> api = CapabilityKey.of("beans-dynamic-api", Api.class);
        CapabilityKey<Supplier<String>> output = CapabilityKey.of(
                "beans-dynamic-output", (Class<Supplier<String>>) (Class<?>) Supplier.class);
        AtomicInteger starts = new AtomicInteger();

        BeanDefinition<NoConfig, Supplier<String>> definition = Beans.component("beans-dynamic-consumer")
                .with(Beans.dynamicProxyRequired(api))
                .<Supplier<String>>create(proxy -> {
                    starts.incrementAndGet();
                    return () -> proxy.value();
                })
                .provide(output)
                .build();

        ComponentHandle<NoConfig> handle = runtime.mount("beans-dynamic-consumer", definition);
        assertEquals(ComponentState.WAITING, settle(handle));
        RegistrationHandle first = runtime.provide(api, new ApiValue("v1"));
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertEquals("v1", runtime.root().view().require(output).get());

        runtime.revoke(first);
        runtime.provide(api, new ApiValue("v2"));
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertEquals(1, starts.get(), "dynamic provider replacement must not recreate the bean");
        assertEquals("v2", runtime.root().view().require(output).get());
    }

    @Test
    void dynamicFactoriesExposeExplicitLeasedCallEntrypoint() throws Exception {
        CapabilityKey<Api> api = CapabilityKey.of("beans-explicit-dynamic", Api.class);
        AtomicReference<DynamicCapability<Api>> dynamic = new AtomicReference<>();

        BeanDependency<DynamicCapability<Api>> dependency = Beans.dynamicOptional(api);
        assertEquals(CapabilityBinding.DYNAMIC, dependency.requirement().binding());

        BeanDefinition<NoConfig, Boolean> definition = Beans.component("beans-explicit-consumer")
                .with(dependency)
                .<Boolean>create(capability -> {
                    dynamic.set(capability);
                    return capability.available();
                })
                .build();
        ComponentHandle<NoConfig> handle = runtime.mount("beans-explicit-consumer", definition);
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertFalse(dynamic.get().available());

        runtime.provide(api, new ApiValue("v1"));
        assertEquals(ComponentState.ACTIVE, settle(handle));
        assertTrue(dynamic.get().available());
        assertEquals("v1", dynamic.get().call(Api::value));
    }

    @Test
    void outputKeysAreReadOnlyAndPreserveDeclarationOrder() {
        CapabilityKey<String> first = CapabilityKey.of("beans-key-first", String.class);
        CapabilityKey<Object> second = CapabilityKey.of("beans-key-second", Object.class);
        BeanDefinition<NoConfig, Service> definition = Beans.component("beans-keys")
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
        ConfigNormalizer invalid = config -> new Object();
        BeanDefinition<Prefix, String> definition =
                (BeanDefinition) Beans.component("beans-bad-normalizer", Prefix.class)
                        .create(config -> config.value())
                        .normalizeConfig((ConfigNormalizer<Prefix>) invalid)
                        .build();

        TransactionRejectedException rejected = assertRejected(DiagnosticCode.INVALID_CONFIG,
                () -> runtime.mount("beans-bad-normalizer", definition, new Prefix("x")));
        assertTrue(rejected.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.message().contains("invalid config type")),
                () -> rejected.diagnostics().toString());
    }
    private ComponentState settle(ComponentHandle<?> handle) throws Exception {
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
