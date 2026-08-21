package io.knotra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class StructuralMutationTest {
    static final CapabilityKey<String> TEXT = CapabilityKey.of("text", String.class);
    static final CapabilityKey<Number> NUMBER = CapabilityKey.of("number", Number.class);
    static final CapabilityKey<Object> TEXT_LIST = CapabilityKey.of("text", Object.class);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    @Test
    void primitiveCapabilityKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CapabilityKey.of("bad", int.class));
    }

    @Test
    void duplicateRequirementKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ComponentDescriptor(
                "bad",
                List.of(CapabilityRequirement.required(TEXT), CapabilityRequirement.optional(TEXT)).stream()
                        .collect(java.util.stream.Collectors.toSet())));
    }

    @Test
    void noConfigIsExplicitAndNonNull() throws Exception {
        var handle = TestKit.mount(runtime, runtime.root(), "empty",
                (context, config) -> assertEquals(NoConfig.INSTANCE, config));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
    }

    @Test
    void defaultIdsAndRootNoConfigMountRemoveCommonBoilerplate() throws Exception {
        ComponentFactory<NoConfig> factory = new ComponentFactory<>() {
            @Override
            public Component<NoConfig> create() {
                return new TestKit.Scripted<>(
                        ComponentDescriptor.of(),
                        (context, config) -> assertSame(NoConfig.INSTANCE, config));
            }
        };

        ComponentHandle<NoConfig> handle = runtime.mount("defaults", factory);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(factory.getClass().getName(), handle.factoryId());
        assertEquals(factory.getClass().getName(), handle.componentId());
    }

    @Test
    void nullConfigIsRejectedInsteadOfNormalizedToNoConfig() {
        TestKit.assertRejected(() -> runtime.transact(mutation -> mutation.mount(
                runtime.root(),
                "bad-null",
                TestKit.factory("bad-null", new TestKit.Scripted<>(
                        ComponentDescriptor.named("bad-null"), (context, config) -> {})),
                (NoConfig) null)), DiagnosticCode.INVALID_CONFIG);
        assertTrue(runtime.snapshot().components().isEmpty());
    }

    @Test
    void configSchemaNormalizesAndValidatesOnceAtMountPreparation() throws Exception {
        AtomicInteger validations = new AtomicInteger();
        ComponentFactory<String> factory = new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "configured";
            }

            @Override
            public Component<String> create() {
                return new TestKit.Scripted<>(
                        ComponentDescriptor.named("configured"),
                        (context, config) -> assertEquals("value", config));
            }

            @Override
            public String normalizeConfig(String config) {
                validations.incrementAndGet();
                if (config.isBlank()) {
                    throw new IllegalArgumentException("blank");
                }
                return config.trim();
            }
        };
        var good = runtime.transact(mutation ->
                mutation.mount(runtime.root(), "configured", factory, " value "));
        TestKit.assertCommitted(good);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(good.value()).call());
        assertEquals(1, validations.get());
        assertEquals(1, good.value().configRevision());

        TestKit.assertRejected(() -> runtime.transact(mutation ->
                mutation.mount(runtime.root(), "other", factory, " ")),
                DiagnosticCode.INVALID_CONFIG);
    }

    @Test
    void descriptorIsFrozenOnceEvenForStatefulComponentImplementation() {
        AtomicInteger descriptorCalls = new AtomicInteger();
        Component<NoConfig> component = new Component<>() {
            @Override
            public ComponentDescriptor descriptor() {
                descriptorCalls.incrementAndGet();
                return ComponentDescriptor.named("frozen", CapabilityRequirement.required(TEXT));
            }

            @Override
            public void start(ActivationContext context, NoConfig config) {
                assertNotNull(context.find(TEXT));
            }
        };
        var handle = runtime.transact(mutation -> mutation.mount(
                runtime.root(), "frozen", TestKit.factory("frozen", component),
                NoConfig.INSTANCE)).value();
        assertEquals(1, descriptorCalls.get());
        assertEquals(ComponentState.WAITING, handle.state());
    }

    @Test
    void hostCapabilityIsVisibleAndTyped() {
        TestKit.provide(runtime, runtime.root(), TEXT, "root");
        assertEquals("root", runtime.root().view().require(TEXT));
        assertEquals(Optional.of("root"), runtime.root().view().find(TEXT));
    }

    @Test
    void capabilityNameHasOneExactJavaType() {
        TestKit.provide(runtime, runtime.root(), TEXT, "value");
        TestKit.assertRejected(() -> runtime.transact(mutation ->
                mutation.provide(runtime.root(), TEXT_LIST, List.of("value"))),
                DiagnosticCode.CAPABILITY_TYPE_CONFLICT);
    }

    @Test
    void valueMustMatchCapabilityKeyType() {
        TestKit.assertRejected(() -> provideWrongValueType(),
                DiagnosticCode.CAPABILITY_TYPE_CONFLICT);
    }

    private TransactionReceipt<RegistrationHandle> provideWrongValueType() {
        return runtime.transact(action -> {
            try {
                var provide = RuntimeTransaction.class.getMethod(
                        "provide",
                        ContextHandle.class,
                        CapabilityKey.class,
                        Object.class);
                provide.setAccessible(true);
                provide.invoke(action, runtime.root(), NUMBER, "bad");
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
            return null;
        });
    }

    @Test
    void childShadowsParentAndFallsBackAfterRevoke() {
        ContextHandle child = TestKit.child(runtime, runtime.root(), "workspace");
        var rootRegistration = TestKit.provide(runtime, runtime.root(), TEXT, "root");
        TestKit.provide(runtime, child, TEXT, "child");
        assertEquals("child", child.view().require(TEXT));
        TestKit.assertCommitted(runtime.transact(mutation -> {
            mutation.revoke(rootRegistration);
            return null;
        }));
        assertEquals("child", child.view().require(TEXT));
    }

    @Test
    void contextSlotCannotBeOccupiedTwice() {
        TestKit.provide(runtime, runtime.root(), TEXT, "first");
        TestKit.assertRejected(() -> runtime.transact(mutation ->
                mutation.provide(runtime.root(), TEXT, "second")),
                DiagnosticCode.CAPABILITY_SLOT_OCCUPIED);
        assertEquals("first", runtime.root().view().require(TEXT));
    }

    @Test
    void siblingContextNamesAreUnique() {
        TestKit.child(runtime, runtime.root(), "workspace");
        TestKit.assertRejected(() -> runtime.transact(mutation ->
                mutation.childContext(runtime.root(), "workspace")),
                DiagnosticCode.INVALID_LIFECYCLE_OPERATION);
    }

    @Test
    void failedMultiOperationTransactionHasNoPartialCommit() {
        long generation = runtime.snapshot().generation();
        TestKit.assertRejected(() -> runtime.transact(mutation -> {
            mutation.childContext(runtime.root(), "new-child");
            mutation.provide(runtime.root(), TEXT, "value");
            mutation.mount(runtime.root(), "duplicate",
                    TestKit.factory("duplicate", new TestKit.Scripted<>(
                            ComponentDescriptor.named("duplicate"), (context, config) -> {})));
            mutation.mount(runtime.root(), "duplicate",
                    TestKit.factory("duplicate", new TestKit.Scripted<>(
                            ComponentDescriptor.named("duplicate"), (context, config) -> {})));
            return "unused";
        }), DiagnosticCode.INVALID_MOUNT_ID);
        assertEquals(generation, runtime.snapshot().generation());
        assertTrue(runtime.root().view().find(TEXT).isEmpty());
        assertTrue(runtime.snapshot().contexts().stream()
                .noneMatch(context -> context.name().equals("new-child")));
    }

    @Test
    void provisionalHandlesCanSupportLaterIntentsInSameTransaction() throws Exception {
        var result = runtime.transact(mutation -> {
            var handle = mutation.mount(runtime.root(), "first",
                    TestKit.factory("first", new TestKit.Scripted<>(
                            ComponentDescriptor.named("first"), (context, config) -> {})),
                    "one");
            mutation.reconfigure(handle, "two");
            return handle;
        });
        TestKit.assertCommitted(result);
        assertEquals(2, result.value().configRevision());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(result.value()).call());
    }

    @Test
    void reconfigureRejectsSchemaNullResult() throws Exception {
        AtomicReference<String> lastConfig = new AtomicReference<>();
        ComponentFactory<String> schemaFactory = new ComponentFactory<>() {
            @Override
            public String factoryId() {
                return "null-schema";
            }

            @Override
            public Component<String> create() {
                return new TestKit.Scripted<>(
                        ComponentDescriptor.named("null-schema"),
                        (context, config) -> lastConfig.set(config));
            }

            @Override
            public String normalizeConfig(String config) {
                return "one".equals(config) ? config : null;
            }
        };
        TransactionReceipt<ComponentHandle<String>> mounted = runtime.transact(mutation ->
                mutation.mount(runtime.root(), "null-schema", schemaFactory, "one"));
        TestKit.assertCommitted(mounted);
        ComponentHandle<String> handle = mounted.value();
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals("one", lastConfig.get());

        TestKit.assertRejected(() -> runtime.transact(mutation ->
                mutation.reconfigure(handle, "two")),
                DiagnosticCode.INVALID_CONFIG);
        assertEquals(1, handle.configRevision());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals("one", lastConfig.get());
    }

    @Test
    void failedTransactionDoesNotLeakUsableProvisionalHandles() throws Exception {
        AtomicReference<ComponentHandle<NoConfig>> provisional = new AtomicReference<>();
        TestKit.assertRejected(() -> runtime.transact(mutation -> {
            var handle = mutation.mount(runtime.root(), "leak",
                    TestKit.factory("leak", new TestKit.Scripted<>(
                            ComponentDescriptor.named("leak"), (context, config) -> {})));
            provisional.set(handle);
            mutation.provide(runtime.root(), TEXT, "occupied");
            mutation.provide(runtime.root(), TEXT, "occupied-again");
            return handle;
        }), DiagnosticCode.CAPABILITY_SLOT_OCCUPIED);
        assertEquals(ComponentState.DISPOSED, provisional.get().state());
        assertTrue(runtime.snapshot().components().stream()
                .noneMatch(component -> component.mountId().equals("leak")));
    }


    @Test
    void factoryAndSchemaUserCodeRunsOutsideCoordinatorLock() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var mountFuture = executor.submit(() -> runtime.transact(mutation -> mutation.mount(
                    runtime.root(),
                    "blocked",
                    new ComponentFactory<NoConfig>() {
                        @Override
                        public String factoryId() {
                            return "blocked";
                        }

                        @Override
                        public Component<NoConfig> create() {
                            entered.countDown();
                            gate.join();
                            return new TestKit.Scripted<>(
                                    ComponentDescriptor.named("blocked"),
                                    (context, config) -> {});
                        }
                    },
                    NoConfig.INSTANCE)));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            TestKit.provide(runtime, runtime.root(), TEXT, "independent");
            gate.complete(null);
            TestKit.assertCommitted(mountFuture.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void mountIdsAreUniqueWithinContextButReusableAfterDisposal() throws Exception {
        var first = TestKit.mount(runtime, runtime.root(), "same",
                (context, config) -> {});
        assertEquals(ComponentState.ACTIVE, TestKit.settle(first).call());
        TestKit.assertRejected(() -> runtime.transact(mutation -> mutation.mount(
                runtime.root(), "same",
                TestKit.factory("other", new TestKit.Scripted<>(
                        ComponentDescriptor.named("other"), (context, config) -> {})))),
                DiagnosticCode.INVALID_MOUNT_ID);
        assertEquals(ComponentState.DISPOSED, first.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS));
        var replacement = TestKit.mount(runtime, runtime.root(), "same",
                (context, config) -> {});
        assertEquals(ComponentState.ACTIVE, TestKit.settle(replacement).call());
        assertEquals(ComponentOrigin.Kind.HOST, TestKit.component(runtime, replacement).origin().kind());
    }

    @Test
    void mountIdCanRepeatAcrossDifferentContexts() throws Exception {
        ContextHandle child = TestKit.child(runtime, runtime.root(), "other-context");
        var rootHandle = TestKit.mount(runtime, runtime.root(), "same",
                (context, config) -> {});
        var childHandle = TestKit.mount(runtime, child, "same",
                (context, config) -> {});
        assertNotEquals(rootHandle.handleId(), childHandle.handleId());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(rootHandle).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(childHandle).call());
    }

    @Test
    void repeatedSnapshotsAreStable() throws Exception {
        TestKit.provide(runtime, runtime.root(), TEXT, "value");
        TestKit.child(runtime, runtime.root(), "z");
        TestKit.child(runtime, runtime.root(), "a");
        var handle = TestKit.mount(runtime, runtime.root(), "component",
                (context, config) -> context.provide(NUMBER, (Number) 1));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(runtime.snapshot(), runtime.snapshot());
    }

    @Test
    void disposedContextRejectsStructuralMutation() throws Exception {
        ContextHandle child = TestKit.child(runtime, runtime.root(), "closed");
        child.close();
        TestKit.assertRejected(() -> runtime.transact(mutation -> mutation.provide(
                child, NUMBER, (Number) 2)),
                DiagnosticCode.INVALID_LIFECYCLE_OPERATION);
    }

    @Test
    void contextDisposeRemovesHostRegistrationsFromSubtree() throws Exception {
        ContextHandle child = TestKit.child(runtime, runtime.root(), "child");
        TestKit.provide(runtime, runtime.root(), TEXT, "root");
        TestKit.assertCommitted(runtime.transact(mutation ->
                mutation.provide(child, NUMBER, (Number) 7)));
        child.close();
        assertTrue(runtime.root().view().find(NUMBER).isEmpty());
        assertTrue(runtime.root().view().find(TEXT).isPresent());
        assertEquals(ContextState.DISPOSED, child.state());
    }
}
