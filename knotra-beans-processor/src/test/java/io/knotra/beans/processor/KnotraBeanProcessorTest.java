package io.knotra.beans.processor;

import io.knotra.CapabilityKey;
import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.NoConfig;
import io.knotra.RegistrationHandle;
import io.knotra.RuntimeSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.processing.Processor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.net.URLClassLoader;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KnotraBeanProcessorTest {

    @TempDir
    Path temporaryDirectory;

    KnotraRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void serviceLoaderDiscoversProcessorAndValidNoConfigBeanMounts() throws Exception {
        assertTrue(ServiceLoader.load(Processor.class, getClass().getClassLoader()).stream()
                .anyMatch(provider -> provider.type() == KnotraBeanProcessor.class));

        Path firstCompile = temporaryDirectory.resolve("first");
        assertTrue(CompilerKit.compile(firstCompile, validSources()));
        CompilerKit.Compilation first = CompilerKit.lastCompilation();
        CompilerKit.assertSuccess(first);
        String source = first.generatedSource("ValidBean_KnotraFactory.java");
        assertGeneratedInvariants(source);
        assertEquals(1, countGeneratedSources(first));

        Path secondCompile = temporaryDirectory.resolve("second");
        assertTrue(CompilerKit.compile(secondCompile, validSources()));
        CompilerKit.Compilation second = CompilerKit.lastCompilation();
        CompilerKit.assertSuccess(second);
        assertEquals(source, second.generatedSource("ValidBean_KnotraFactory.java"));
        assertEquals(1, countGeneratedSources(second));

        runtime = KnotraRuntime.create();
        try (URLClassLoader loader = second.classLoader()) {
            Class<?> storageType = loader.loadClass("demo.Storage");
            Class<?> routerType = loader.loadClass("demo.Router");
            Object storage = accessibleConstructor(
                    loader.loadClass("demo.MemoryStorage")).newInstance();
            Object router = accessibleConstructor(
                    loader.loadClass("demo.StaticRouter")).newInstance();
            provide("processor.storage", storageType, storage);
            provide("processor.router", routerType, router);

            Object factory = loader.loadClass("demo.ValidBean_KnotraFactory")
                    .getConstructor().newInstance();
            Object sameFactory = loader.loadClass("demo.ValidBean_KnotraFactory")
                    .getConstructor().newInstance();
            Method definition = factory.getClass().getMethod("definition");
            Object firstDefinition = definition.invoke(factory);
            Object secondDefinition = definition.invoke(sameFactory);
            Method descriptor = firstDefinition.getClass().getMethod("descriptor");
            Method factoryId = factory.getClass().getMethod("factoryId");
            assertEquals("processor.valid", factoryId.invoke(factory));
            assertEquals(descriptor.invoke(firstDefinition), descriptor.invoke(secondDefinition));

            ComponentHandle<NoConfig> handle = mountNoConfig("valid", factory);
            assertEquals(ComponentState.ACTIVE, settle(handle));

            Class<?> primaryType = loader.loadClass("demo.Primary");
            Class<?> secondaryType = loader.loadClass("demo.Secondary");
            Object primary = require("processor.primary", primaryType);
            Object secondary = require("processor.secondary", secondaryType);
            assertEquals(primary, secondary);
            Method render = accessibleMethod(primaryType, "render");
            assertEquals("storage=memory;feature=absent;router=dynamic", render.invoke(primary));

            Class<?> eventsClass = loader.loadClass("demo.ValidBean");
            List<?> events = (List<?>) accessibleField(eventsClass, "EVENTS").get(null);
            assertEquals(List.of("init"), events);

            handle.close();
            assertEquals(List.of("init", "destroy"), events);
            assertRuntimeHasNoActivationOwnedOutputs();
        }
    }

    @Test
    void typedConfigNormalizerAndAsyncDestroySupportRuntimeReconfigure() throws Exception {
        Path compileDirectory = temporaryDirectory.resolve("configured");
        assertTrue(CompilerKit.compile(compileDirectory, configuredSources()));
        CompilerKit.Compilation compilation = CompilerKit.lastCompilation();
        CompilerKit.assertSuccess(compilation);
        assertGeneratedInvariants(compilation.generatedSource("ConfiguredBean_KnotraFactory.java"));

        runtime = KnotraRuntime.create();
        try (URLClassLoader loader = compilation.classLoader()) {
            Class<?> configType = loader.loadClass("demo.Config");
            Class<?> serviceType = loader.loadClass("demo.ConfigService");
            Object factory = loader.loadClass("demo.ConfiguredBean_KnotraFactory")
                    .getConstructor().newInstance();
            Object firstConfig = accessibleConstructor(configType, String.class)
                    .newInstance(" one ");
            Object secondConfig = accessibleConstructor(configType, String.class)
                    .newInstance(" two ");

            ComponentHandle<?> handle = mountConfigured("configured", factory, firstConfig);
            assertEquals(ComponentState.ACTIVE, settle(handle));
            Object service = require("processor.config-service", serviceType);
            assertEquals("one", accessibleMethod(serviceType, "value").invoke(service));

            Class<?> beanClass = loader.loadClass("demo.ConfiguredBean");
            List<?> events = (List<?>) accessibleField(beanClass, "EVENTS").get(null);
            assertEquals(List.of("create:one", "init:one"), events);

            assertEquals(ComponentState.ACTIVE, reconfigure(handle, secondConfig));
            assertEquals(List.of(
                    "create:one", "init:one", "destroy:one", "create:two", "init:two"), events);

            java.lang.reflect.Field blocking = accessibleField(beanClass, "BLOCK_FINAL_DISPOSAL");
            java.lang.reflect.Field gateField = accessibleField(beanClass, "DISPOSAL_GATE");
            java.lang.reflect.Field destroyEnteredField =
                    accessibleField(beanClass, "DESTROY_ENTERED");
            blocking.setBoolean(null, true);
            CompletableFuture<Void> disposalGate =
                    (CompletableFuture<Void>) gateField.get(null);
            CompletableFuture<Void> destroyEntered =
                    (CompletableFuture<Void>) destroyEnteredField.get(null);
            CompletionStage<?> disposal = ((ComponentHandle<?>) handle).disposeAsync();
            destroyEntered.get(10, TimeUnit.SECONDS);
            assertFalse(disposal.toCompletableFuture().isDone());
            disposalGate.complete(null);
            assertEquals(ComponentState.DISPOSED, disposal.toCompletableFuture().get(10, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("negativeCases")
    void invalidBeansAreRejected(String name, String body, String expectedDiagnostic) throws Exception {
        String defaultAnnotation = body.contains("@KnotraBean") ? "" : "@KnotraBean(id = \"bad\")\n";
        String source = """
                package demo;

                import io.knotra.beans.annotation.*;
                import java.util.Optional;
                import java.util.concurrent.CompletionStage;

                %s%s
                """.formatted(defaultAnnotation, body);
        Path directory = temporaryDirectory.resolve(name);
        boolean success = CompilerKit.compile(
                directory, List.of(new CompilerKit.Source("demo.BadBean", source)));
        assertFalse(success, name + " must not compile");
        List<String> errors = CompilerKit.errorMessages(CompilerKit.lastCompilation());
        assertTrue(errors.stream().anyMatch(error -> error.contains(expectedDiagnostic)),
                () -> name + " expected diagnostic '" + expectedDiagnostic + "' but got " + errors);
        assertEquals(0, countGeneratedSources(CompilerKit.lastCompilation()));
    }

    @Test
    void existingGeneratedFactoryNameIsRejectedWithoutWritingSource() throws Exception {
        Path directory = temporaryDirectory.resolve("generated-name-conflict");
        String bean = """
                package demo;

                import io.knotra.beans.annotation.*;

                @KnotraBean(id = "conflict")
                class BadBean {
                    @KnotraConstructor BadBean() {}
                }
                """;
        String existingFactory = """
                package demo;

                class BadBean_KnotraFactory {}
                """;
        boolean success = CompilerKit.compile(directory, List.of(
                new CompilerKit.Source("demo.BadBean", bean),
                new CompilerKit.Source("demo.BadBean_KnotraFactory", existingFactory)));
        assertFalse(success);
        List<String> errors = CompilerKit.errorMessages(CompilerKit.lastCompilation());
        assertTrue(errors.stream().anyMatch(error -> error.contains(
                        "cannot generate demo.BadBean_KnotraFactory")),
                () -> "expected generated-name diagnostic but got " + errors);
        assertEquals(0, countGeneratedSources(CompilerKit.lastCompilation()));
    }

    private static List<CompilerKit.Source> validSources() {
        String source = """
                package demo;

                import io.knotra.beans.annotation.*;
                import java.util.List;
                import java.util.Optional;

                interface Storage { String load(); }
                interface Feature { String name(); }
                interface Router { String route(); }
                interface Primary { String render(); }
                interface Secondary { String describe(); }

                final class MemoryStorage implements Storage {
                    public String load() { return "memory"; }
                }

                final class StaticRouter implements Router {
                    public String route() { return "dynamic"; }
                }

                @KnotraBean(
                        id = "processor.valid",
                        outputs = {
                            @KnotraOutput(name = "processor.primary", contract = Primary.class),
                            @KnotraOutput(name = "processor.secondary", contract = Secondary.class)
                        })
                class ValidBean implements Primary, Secondary {
                    static final List<String> EVENTS = new java.util.ArrayList<>();

                    private final Storage storage;
                    private final Optional<Feature> feature;
                    private final Router router;

                    @KnotraConstructor
                    ValidBean(
                            @KnotraRequire(name = "processor.storage", contract = Storage.class) Storage storage,
                            @KnotraOptional(name = "processor.feature", contract = Feature.class) Optional<Feature> feature,
                            @KnotraDynamic(name = "processor.router", contract = Router.class) Router router) {
                        this.storage = storage;
                        this.feature = feature;
                        this.router = router;
                    }

                    @KnotraInit
                    void initialize() { EVENTS.add("init"); }

                    @KnotraDestroy
                    void destroy() { EVENTS.add("destroy"); }

                    public String render() {
                        return "storage=" + storage.load()
                                + ";feature=" + feature.map(Feature::name).orElse("absent")
                                + ";router=" + router.route();
                    }

                    public String describe() { return render(); }
                }
                """;
        return List.of(new CompilerKit.Source("demo.ValidBean", source));
    }

    private static List<CompilerKit.Source> configuredSources() {
        String source = """
                package demo;

                import io.knotra.beans.annotation.*;
                import java.util.List;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;

                record Config(String value) {}

                interface ConfigService { String value(); }

                @KnotraBean(
                        id = "processor.configured",
                        config = Config.class,
                        outputs = @KnotraOutput(
                                name = "processor.config-service",
                                contract = ConfigService.class))
                class ConfiguredBean implements ConfigService {
                    static final List<String> EVENTS = new java.util.concurrent.CopyOnWriteArrayList<>();
                    static volatile boolean BLOCK_FINAL_DISPOSAL = false;
                    static final CompletableFuture<Void> DISPOSAL_GATE = new CompletableFuture<>();
                    static final CompletableFuture<Void> DESTROY_ENTERED = new CompletableFuture<>();

                    private final Config config;

                    @KnotraConstructor
                    ConfiguredBean(@KnotraConfig Config config) {
                        this.config = config;
                        EVENTS.add("create:" + config.value());
                    }

                    @KnotraNormalizeConfig
                    static Config normalize(Config config) {
                        if (config.value().isBlank()) {
                            throw new IllegalArgumentException("blank config");
                        }
                        return new Config(config.value().trim());
                    }

                    @KnotraInit
                    void initialize() { EVENTS.add("init:" + config.value()); }

                    @KnotraDestroy(async = true)
                    CompletionStage<Void> destroy() {
                        EVENTS.add("destroy:" + config.value());
                        DESTROY_ENTERED.complete(null);
                        return BLOCK_FINAL_DISPOSAL ? DISPOSAL_GATE : CompletableFuture.completedFuture(null);
                    }

                    public String value() { return config.value(); }
                }
                """;
        return List.of(new CompilerKit.Source("demo.ConfiguredBean", source));
    }

    private static Stream<Object[]> negativeCases() {
        return Stream.of(
                new Object[] {"zero constructors", """
                        class BadBean {
                            BadBean() {}
                        }
                        """, "exactly one @KnotraConstructor"},
                new Object[] {"two constructors", """
                        class BadBean {
                            @KnotraConstructor BadBean() {}
                            @KnotraConstructor BadBean(@KnotraRequire(
                                    name = "x", contract = String.class) String value) { this(); }
                        }
                        """, "exactly one @KnotraConstructor"},
                new Object[] {"unannotated parameter", """
                        class BadBean {
                            @KnotraConstructor BadBean(String value) {}
                        }
                        """, "exactly one of"},
                new Object[] {"optional wrong type", """
                        class BadBean {
                            @KnotraConstructor BadBean(@KnotraOptional(
                                    name = "x", contract = String.class) String value) {}
                        }
                        """, "Optional<contract>"},
                new Object[] {"dynamic non-interface", """
                        class BadBean {
                            @KnotraConstructor BadBean(@KnotraDynamic(
                                    name = "x", contract = String.class) String value) {}
                        }
                        """, "contract must be an interface"},
                new Object[] {"config mismatch", """
                        @KnotraBean(id = "bad", config = Integer.class)
                        class BadBean {
                            @KnotraConstructor BadBean(@KnotraConfig String config) {}
                        }
                        """, "config type"},
                new Object[] {"output incompatible", """
                        @KnotraOutput(
                                name = "x", contract = Integer.class)
                        class BadBean {
                            @KnotraConstructor BadBean() {}
                        }
                        """, "not assignable to output contract"},
                new Object[] {"duplicate name", """
                        @KnotraOutput(
                                name = "x", contract = String.class)
                        class BadBean {
                            @KnotraConstructor BadBean(@KnotraRequire(
                                    name = "x", contract = String.class) String value) {}
                        }
                        """, "duplicate capability name"},
                new Object[] {"bad init", """
                        class BadBean {
                            @KnotraConstructor BadBean() {}
                            @KnotraInit static void initialize() {}
                        }
                        """, "zero-argument instance method"},
                new Object[] {"bad async destroy", """
                        class BadBean {
                            @KnotraConstructor BadBean() {}
                            @KnotraDestroy(async = true) String destroy() { return "x"; }
                        }
                        """, "CompletionStage<Void>"},
                new Object[] {"bad normalizer", """
                        class BadBean {
                            @KnotraConstructor BadBean() {}
                            @KnotraNormalizeConfig String normalize() { return "x"; }
                        }
                        """, "static method with one config parameter"},
                new Object[] {"blank id", """
                        @KnotraBean(id = "")
                        class BadBean {
                            @KnotraConstructor BadBean() {}
                        }
                        """, "id is required"},
                new Object[] {"parameterized required contract", """
                        interface Contract<T> { T value(); }

                        @KnotraBean(id = "bad")
                        class BadBean {
                            @KnotraConstructor BadBean(@KnotraRequire(
                                    name = "x", contract = Contract.class) Contract<String> value) {}
                        }
                        """, "contract must not be a generic or parameterized type"},
                new Object[] {"optional nested type argument", """
                        class BadBean {
                            @KnotraConstructor BadBean(@KnotraOptional(
                                    name = "x", contract = java.util.List.class)
                                    Optional<java.util.List<String>> value) {}
                        }
                        """, "contract must not be a generic or parameterized type"},
                new Object[] {"parameterized config", """
                        @KnotraBean(id = "bad", config = java.util.List.class)
                        class BadBean {
                            @KnotraConstructor BadBean(@KnotraConfig java.util.List<String> config) {}
                        }
                        """, "parameter type must not be a generic or parameterized type"},
                new Object[] {"primitive config", """
                        @KnotraBean(id = "bad", config = int.class)
                        class BadBean {
                            @KnotraConstructor BadBean(@KnotraConfig int config) {}
                        }
                        """, "config type must not be primitive"},
                new Object[] {"generic constructor", """
                        class BadBean {
                            @KnotraConstructor <T> BadBean() {}
                        }
                        """, "constructor must not declare type parameters"},
                new Object[] {"private nested config", """
                        @KnotraBean(id = "bad", config = BadBean.Config.class)
                        class BadBean {
                            private record Config(String value) {}

                            @KnotraConstructor BadBean(@KnotraConfig BadBean.Config config) {}
                        }
                        """, "private access"},
                new Object[] {"private nested required contract", """
                        class BadBean {
                            private interface Contract { String value(); }

                            @KnotraConstructor BadBean(@KnotraRequire(
                                    name = "x", contract = Contract.class) Contract value) {}
                        }
                        """, "contract must be accessible"},
                new Object[] {"private nested optional contract", """
                        class BadBean {
                            private interface Contract { String value(); }

                            @KnotraConstructor BadBean(@KnotraOptional(
                                    name = "x", contract = Contract.class) Optional<Contract> value) {}
                        }
                        """, "contract must be accessible"},
                new Object[] {"private nested dynamic contract", """
                        class BadBean {
                            private interface Contract { String value(); }

                            @KnotraConstructor BadBean(@KnotraDynamic(
                                    name = "x", contract = Contract.class) Contract value) {}
                        }
                        """, "contract must be accessible"},
                new Object[] {"private nested output contract", """
                        @KnotraOutput(name = "x", contract = BadBean.Contract.class)
                        class BadBean {
                            private interface Contract { String value(); }

                            @KnotraConstructor BadBean() {}

                            public String value() { return "x"; }
                        }
                        """, "private access"});
    }

    private static void assertGeneratedInvariants(String source) {
        assertFalse(source.contains("java.lang.reflect"), "generated source must not use reflection");
        assertFalse(source.contains("System.currentTimeMillis"), "generated source must not embed a timestamp");
        assertFalse(source.contains("Instant.now"), "generated source must not embed a timestamp");
        assertTrue(source.contains("contractClass("), "generated source must use compile-time class literals");
    }

    private static int countGeneratedSources(CompilerKit.Compilation compilation) throws Exception {
        try (var files = Files.walk(compilation.generatedSources())) {
            return (int) files
                    .filter(path -> path.toString().endsWith(".java"))
                    .count();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void provide(String name, Class<?> type, Object value) {
        runtime.provide(new CapabilityKey(name, type), value);
    }

    private static Constructor<?> accessibleConstructor(Class<?> type, Class<?>... parameters)
            throws ReflectiveOperationException {
        Constructor<?> constructor = type.getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        return constructor;
    }

    private static Method accessibleMethod(Class<?> type, String name)
            throws ReflectiveOperationException {
        Method method = type.getMethod(name);
        method.setAccessible(true);
        return method;
    }

    private static Field accessibleField(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object require(String name, Class<?> type) {
        return runtime.root().view().require(new CapabilityKey(name, type));
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ComponentHandle<NoConfig> mountNoConfig(String mountId, Object factory) {
        return runtime.mount(mountId, (ComponentFactory<NoConfig>) factory);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ComponentHandle<?> mountConfigured(String mountId, Object factory, Object config) {
        return runtime.mount(mountId, (ComponentFactory) factory, config);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ComponentState reconfigure(ComponentHandle<?> handle, Object config) throws Exception {
        Object next = ((ComponentHandle) handle).reconfigureAsync(config);
        return (ComponentState) ((CompletionStage<?>) next)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private ComponentState settle(ComponentHandle<?> handle) throws Exception {
        return handle.whenSettled().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }


    private void assertRuntimeHasNoActivationOwnedOutputs() {
        for (RuntimeSnapshot.RegistrationSnapshot registration : runtime.snapshot().registrations()) {
            if (registration.owner().kind()
                    == RuntimeSnapshot.RegistrationOwnerKind.ACTIVATION) {
                throw new AssertionError("activation output was not revoked: " + registration);
            }
        }
    }
}
