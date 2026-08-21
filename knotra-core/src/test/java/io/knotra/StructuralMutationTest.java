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
        var handle = TestKit.mount(runtime, runtime.rootContext(), "empty",
                (context, config) -> assertEquals(NoConfig.INSTANCE, config));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
    }

    @Test
    void nullConfigIsRejectedInsteadOfNormalizedToNoConfig() {
        var result = runtime.mutate(mutation -> mutation.mount(
                runtime.rootContext(),
                "bad-null",
                TestKit.factory("bad-null", new TestKit.Scripted<>(
                        ComponentDescriptor.of("bad-null"), (context, config) -> {})),
                null));
        TestKit.assertRejected(result, DiagnosticCode.INVALID_LIFECYCLE_OPERATION);
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
                        ComponentDescriptor.of("configured"),
                        (context, config) -> assertEquals("value", config));
            }

            @Override
            public Optional<ConfigSchema<String>> configSchema() {
                return Optional.of(raw -> {
                    validations.incrementAndGet();
                    if (String.valueOf(raw).isBlank()) {
                        throw new IllegalArgumentException("blank");
                    }
                    return String.valueOf(raw).trim();
                });
            }
        };
        var good = runtime.mutate(mutation ->
                mutation.mount(runtime.rootContext(), "configured", factory, " value "));
        TestKit.assertCommitted(good);
        assertEquals(ComponentState.ACTIVE, TestKit.settle(good.value()).call());
        assertEquals(1, validations.get());
        assertEquals(1, good.value().configRevision());

        var bad = runtime.mutate(mutation ->
                mutation.mount(runtime.rootContext(), "other", factory, " "));
        TestKit.assertRejected(bad, DiagnosticCode.INVALID_LIFECYCLE_OPERATION);
    }

    @Test
    void descriptorIsFrozenOnceEvenForStatefulComponentImplementation() {
        AtomicInteger descriptorCalls = new AtomicInteger();
        Component<NoConfig> component = new Component<>() {
            @Override
            public ComponentDescriptor descriptor() {
                descriptorCalls.incrementAndGet();
                return ComponentDescriptor.of("frozen", CapabilityRequirement.required(TEXT));
            }

            @Override
            public void start(ActivationContext context, NoConfig config) {
                assertNotNull(context.find(TEXT));
            }
        };
        var handle = runtime.mutate(mutation -> mutation.mount(
                runtime.rootContext(), "frozen", TestKit.factory("frozen", component),
                NoConfig.INSTANCE)).value();
        assertEquals(1, descriptorCalls.get());
        assertEquals(ComponentState.WAITING, handle.state());
    }

    @Test
    void hostCapabilityIsVisibleAndTyped() {
        TestKit.provide(runtime, runtime.rootContext(), TEXT, "root");
        assertEquals("root", runtime.context().require(TEXT));
        assertEquals(Optional.of("root"), runtime.context().find(TEXT));
    }

    @Test
    void capabilityNameHasOneExactJavaType() {
        TestKit.provide(runtime, runtime.rootContext(), TEXT, "value");
        var conflicting = runtime.mutate(mutation ->
                mutation.provide(runtime.rootContext(), TEXT_LIST, List.of("value")));
        TestKit.assertRejected(conflicting, DiagnosticCode.CAPABILITY_TYPE_CONFLICT);
    }

    @Test
    void valueMustMatchCapabilityKeyType() {
        var result = provideWrongValueType();
        TestKit.assertRejected(result, DiagnosticCode.CAPABILITY_TYPE_CONFLICT);
    }

    private MutationResult<RegistrationHandle> provideWrongValueType() {
        return runtime.mutate(action -> {
            try {
                var provide = RuntimeMutation.class.getMethod(
                        "provide",
                        ContextHandle.class,
                        CapabilityKey.class,
                        Object.class);
                provide.setAccessible(true);
                provide.invoke(action, runtime.rootContext(), NUMBER, "bad");
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
            return null;
        });
    }

    @Test
    void childShadowsParentAndFallsBackAfterRevoke() {
        ContextHandle child = TestKit.child(runtime, runtime.rootContext(), "workspace");
        var rootRegistration = TestKit.provide(runtime, runtime.rootContext(), TEXT, "root");
        TestKit.provide(runtime, child, TEXT, "child");
        assertEquals("child", child.context().require(TEXT));
        TestKit.assertCommitted(runtime.mutate(mutation -> {
            mutation.revoke(rootRegistration);
            return null;
        }));
        assertEquals("child", child.context().require(TEXT));
    }

    @Test
    void contextSlotCannotBeOccupiedTwice() {
        TestKit.provide(runtime, runtime.rootContext(), TEXT, "first");
        var second = runtime.mutate(mutation ->
                mutation.provide(runtime.rootContext(), TEXT, "second"));
        TestKit.assertRejected(second, DiagnosticCode.CAPABILITY_SLOT_OCCUPIED);
        assertEquals("first", runtime.context().require(TEXT));
    }

    @Test
    void siblingContextNamesAreUnique() {
        TestKit.child(runtime, runtime.rootContext(), "workspace");
        var duplicate = runtime.mutate(mutation ->
                mutation.childContext(runtime.rootContext(), "workspace"));
        TestKit.assertRejected(duplicate, DiagnosticCode.INVALID_LIFECYCLE_OPERATION);
    }

    @Test
    void failedMultiOperationTransactionHasNoPartialCommit() {
        long generation = runtime.snapshot().generation();
        var result = runtime.mutate(mutation -> {
            mutation.childContext(runtime.rootContext(), "new-child");
            mutation.provide(runtime.rootContext(), TEXT, "value");
            mutation.mount(runtime.rootContext(), "duplicate",
                    TestKit.factory("duplicate", new TestKit.Scripted<>(
                            ComponentDescriptor.of("duplicate"), (context, config) -> {})),
                    NoConfig.INSTANCE);
            mutation.mount(runtime.rootContext(), "duplicate",
                    TestKit.factory("duplicate", new TestKit.Scripted<>(
                            ComponentDescriptor.of("duplicate"), (context, config) -> {})),
                    NoConfig.INSTANCE);
            return "unused";
        });
        TestKit.assertRejected(result, DiagnosticCode.INVALID_MOUNT_ID);
        assertEquals(generation, runtime.snapshot().generation());
        assertTrue(runtime.context().find(TEXT).isEmpty());
        assertTrue(runtime.snapshot().contexts().stream()
                .noneMatch(context -> context.name().equals("new-child")));
    }

    @Test
    void provisionalHandlesCanSupportLaterIntentsInSameTransaction() throws Exception {
        var result = runtime.mutate(mutation -> {
            var handle = mutation.mount(runtime.rootContext(), "first",
                    TestKit.factory("first", new TestKit.Scripted<>(
                            ComponentDescriptor.of("first"), (context, config) -> {})),
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
                        ComponentDescriptor.of("null-schema"),
                        (context, config) -> lastConfig.set(config));
            }

            @Override
            public Optional<ConfigSchema<String>> configSchema() {
                return Optional.of(raw -> "one".equals(raw) ? (String) raw : null);
            }
        };
        MutationResult<ComponentHandle<String>> mounted = runtime.mutate(mutation ->
                mutation.mount(runtime.rootContext(), "null-schema", schemaFactory, "one"));
        TestKit.assertCommitted(mounted);
        ComponentHandle<String> handle = mounted.value();
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals("one", lastConfig.get());

        MutationResult<ComponentHandle<String>> rejected = runtime.mutate(mutation ->
                mutation.reconfigure(handle, "two"));
        assertFalse(rejected.committed());
        assertEquals(DiagnosticCode.INVALID_LIFECYCLE_OPERATION,
                rejected.diagnostics().getFirst().code());
        assertEquals(1, handle.configRevision());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals("one", lastConfig.get());
    }

    @Test
    void failedTransactionDoesNotLeakUsableProvisionalHandles() throws Exception {
        var result = runtime.mutate(mutation -> {
            var handle = mutation.mount(runtime.rootContext(), "leak",
                    TestKit.factory("leak", new TestKit.Scripted<>(
                            ComponentDescriptor.of("leak"), (context, config) -> {})),
                    NoConfig.INSTANCE);
            mutation.provide(runtime.rootContext(), TEXT, "occupied");
            mutation.provide(runtime.rootContext(), TEXT, "occupied-again");
            return handle;
        });
        TestKit.assertRejected(result, DiagnosticCode.CAPABILITY_SLOT_OCCUPIED);
        assertThrows(IllegalStateException.class, result::value);
        assertTrue(runtime.snapshot().components().stream()
                .noneMatch(component -> component.mountId().equals("leak")));
    }


    @Test
    void factoryAndSchemaUserCodeRunsOutsideCoordinatorLock() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var mountFuture = executor.submit(() -> runtime.mutate(mutation -> mutation.mount(
                    runtime.rootContext(),
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
                                    ComponentDescriptor.of("blocked"),
                                    (context, config) -> {});
                        }
                    },
                    NoConfig.INSTANCE)));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            TestKit.provide(runtime, runtime.rootContext(), TEXT, "independent");
            gate.complete(null);
            TestKit.assertCommitted(mountFuture.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void mountIdsAreUniqueWithinContextButReusableAfterDisposal() throws Exception {
        var first = TestKit.mount(runtime, runtime.rootContext(), "same",
                (context, config) -> {});
        assertEquals(ComponentState.ACTIVE, TestKit.settle(first).call());
        var conflict = runtime.mutate(mutation -> mutation.mount(
                runtime.rootContext(), "same",
                TestKit.factory("other", new TestKit.Scripted<>(
                        ComponentDescriptor.of("other"), (context, config) -> {})),
                NoConfig.INSTANCE));
        TestKit.assertRejected(conflict, DiagnosticCode.INVALID_MOUNT_ID);
        assertEquals(ComponentState.DISPOSED, first.dispose().toCompletableFuture().get(10, TimeUnit.SECONDS));
        var replacement = TestKit.mount(runtime, runtime.rootContext(), "same",
                (context, config) -> {});
        assertEquals(ComponentState.ACTIVE, TestKit.settle(replacement).call());
        assertEquals(ComponentOrigin.Kind.HOST, TestKit.component(runtime, replacement).origin().kind());
    }

    @Test
    void mountIdCanRepeatAcrossDifferentContexts() throws Exception {
        ContextHandle child = TestKit.child(runtime, runtime.rootContext(), "other-context");
        var rootHandle = TestKit.mount(runtime, runtime.rootContext(), "same",
                (context, config) -> {});
        var childHandle = TestKit.mount(runtime, child, "same",
                (context, config) -> {});
        assertNotEquals(rootHandle.handleId(), childHandle.handleId());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(rootHandle).call());
        assertEquals(ComponentState.ACTIVE, TestKit.settle(childHandle).call());
    }

    @Test
    void repeatedSnapshotsAreStable() throws Exception {
        TestKit.provide(runtime, runtime.rootContext(), TEXT, "value");
        TestKit.child(runtime, runtime.rootContext(), "z");
        TestKit.child(runtime, runtime.rootContext(), "a");
        var handle = TestKit.mount(runtime, runtime.rootContext(), "component",
                (context, config) -> context.provide(NUMBER, (Number) 1));
        assertEquals(ComponentState.ACTIVE, TestKit.settle(handle).call());
        assertEquals(runtime.snapshot(), runtime.snapshot());
    }

    @Test
    void disposedContextRejectsStructuralMutation() throws Exception {
        ContextHandle child = TestKit.child(runtime, runtime.rootContext(), "closed");
        child.close();
        var result = runtime.mutate(mutation -> mutation.provide(
                child, NUMBER, (Number) 2));
        TestKit.assertRejected(result, DiagnosticCode.INVALID_LIFECYCLE_OPERATION);
    }

    @Test
    void contextDisposeRemovesHostRegistrationsFromSubtree() throws Exception {
        ContextHandle child = TestKit.child(runtime, runtime.rootContext(), "child");
        TestKit.provide(runtime, runtime.rootContext(), TEXT, "root");
        TestKit.assertCommitted(runtime.mutate(mutation ->
                mutation.provide(child, NUMBER, (Number) 7)));
        child.close();
        assertTrue(runtime.context().find(NUMBER).isEmpty());
        assertTrue(runtime.context().find(TEXT).isPresent());
        assertEquals(ContextState.DISPOSED, child.state());
    }
}
