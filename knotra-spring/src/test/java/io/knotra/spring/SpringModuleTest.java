package io.knotra.spring;

import io.knotra.CapabilityKey;
import io.knotra.ComponentFactory;
import io.knotra.ComponentState;
import io.knotra.ConfiguredMountHandle;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.DiagnosticCode;
import io.knotra.KnotraRuntime;
import io.knotra.Publication;
import io.knotra.TransactionRejectedException;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class SpringModuleTest {

    static final CapabilityKey<Provider> PROVIDER =
            CapabilityKey.of("spring-test.provider", Provider.class);
    static final CapabilityKey<Provider> WRAPPED_PROVIDER =
            CapabilityKey.of("spring-test.wrapped-provider", Provider.class);
    static final CapabilityKey<ProviderSnapshot> PROVIDER_SNAPSHOT =
            CapabilityKey.of("spring-test.provider-snapshot", ProviderSnapshot.class);
    static final CapabilityKey<ServiceSnapshot> SERVICE =
            CapabilityKey.of("spring-test.service", ServiceSnapshot.class);
    static final CapabilityKey<ConfiguredSnapshot> CONFIGURED =
            CapabilityKey.of("spring-test.configured", ConfiguredSnapshot.class);
    static final CapabilityKey<String> FIRST =
            CapabilityKey.of("spring-test.first", String.class);
    static final CapabilityKey<Integer> SECOND =
            CapabilityKey.of("spring-test.second", Integer.class);
    static final CapabilityKey<String> REFRESH_OUTPUT =
            CapabilityKey.of("spring-test.refresh-output", String.class);
    static final CapabilityKey<LoaderSnapshot> LOADER_SNAPSHOT =
            CapabilityKey.of("spring-test.loader-snapshot", LoaderSnapshot.class);

    KnotraRuntime runtime = KnotraRuntime.create();
    static final AtomicReference<CountingDisposable> refreshDisposable = new AtomicReference<>();
    static final AtomicReference<ClassLoader> internalDestroyLoader = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    interface Provider {
        String value();
    }

    static final class ExternalProvider implements Provider, DisposableBean, AutoCloseable {
        private final String value;
        private boolean closed;
        private final AtomicInteger springDestroys = new AtomicInteger();

        ExternalProvider(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }

        @Override
        public void destroy() {
            springDestroys.incrementAndGet();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    record ProviderSnapshot(Provider value, Optional<Provider> wrapped) {
    }

    record ServiceSnapshot(Provider provider) {
    }

    record ModuleConfig(String value) {
    }

    static final class DisposableModuleConfig implements DisposableBean {
        private final String value;
        private final AtomicInteger destroys = new AtomicInteger();

        DisposableModuleConfig(String value) {
            this.value = value;
        }

        @Override
        public void destroy() {
            destroys.incrementAndGet();
        }
    }

    record ConfiguredSnapshot(String value) {
    }

    record LoaderSnapshot(ClassLoader startLoader) {
    }

    @Configuration
    static class RequiredConfig {
        @Bean
        ServiceSnapshot service(@Qualifier("provider") Provider provider) {
            return new ServiceSnapshot(provider);
        }
    }

    @Configuration
    static class OptionalConfig {
        @Bean
        ProviderSnapshot snapshot(
                @Qualifier("valueProvider") org.springframework.beans.factory.ObjectProvider<Provider> value,
                @Qualifier("wrappedProvider") Optional<Provider> wrapped) {
            return new ProviderSnapshot(value.getIfAvailable(), wrapped);
        }
    }

    @Configuration
    static class TypedConfigConfig {
        @Bean
        ConfiguredSnapshot configured(ModuleConfig config) {
            return new ConfiguredSnapshot(config.value());
        }
    }

    @Configuration
    static class DisposableTypedConfig {
        @Bean
        ConfiguredSnapshot configured(DisposableModuleConfig config) {
            return new ConfiguredSnapshot(config.value);
        }
    }

    @Configuration
    static class LoaderConfig {
        @Bean
        LoaderSnapshot snapshot() {
            return new LoaderSnapshot(Thread.currentThread().getContextClassLoader());
        }

        @Bean
        LoaderTrackedDisposable tracked() {
            return new LoaderTrackedDisposable();
        }
    }

    @Configuration
    static class DifferentLoaderConfig {
    }

    @Configuration
    static class MultiOutputConfig {
        @Bean
        String first() {
            return "first-value";
        }

        @Bean
        Integer second() {
            return 42;
        }
    }

    @Configuration
    static class RefreshFailureConfig {
        @Bean
        CountingDisposable created() {
            CountingDisposable disposable = new CountingDisposable();
            refreshDisposable.set(disposable);
            return disposable;
        }

        @Bean
        @org.springframework.context.annotation.DependsOn("created")
        String boom() {
            throw new IllegalStateException("refresh failed");
        }
    }

    static final class CountingDisposable implements DisposableBean {
        final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void destroy() {
            closeCount.incrementAndGet();
        }
    }

    static final class LoaderTrackedDisposable implements DisposableBean {
        @Override
        public void destroy() {
            internalDestroyLoader.set(Thread.currentThread().getContextClassLoader());
        }
    }

    @Test
    void requiredReplacementRebuildsSpringContextWithoutClosingExternalProvider()
            throws Exception {
        ExternalProvider first = new ExternalProvider("v1");
        ExternalProvider second = new ExternalProvider("v2");
        Publication<Provider> publication = runtime.publish(PROVIDER, first).publication();

        AtomicInteger contextCount = new AtomicInteger();
        MountFactory factory = SpringModules.noConfig("required-spring")
                .annotatedClasses(RequiredConfig.class)
                .customizer(context -> contextCount.incrementAndGet())
                .required("provider", PROVIDER)
                .expose(SERVICE)
                .build();
        MountHandle handle = runtime.mount("required-spring", factory);
        ServiceAssertions.assertActive(handle);
        ServiceSnapshot firstSnapshot = runtime.root().view().require(SERVICE);
        assertSame(first, firstSnapshot.provider());
        assertEquals(1, contextCount.get());

        publication.update(second).awaitSettled(Duration.ofSeconds(10));


        ServiceSnapshot secondSnapshot = runtime.root().view().require(SERVICE);
        assertNotSame(firstSnapshot, secondSnapshot);
        assertSame(second, secondSnapshot.provider());
        assertEquals(2, contextCount.get());

        handle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertFalse(first.closed);
        assertFalse(second.closed);
        assertEquals(0, first.springDestroys.get());
        assertEquals(0, second.springDestroys.get());
    }

    @Test
    void optionalValueAndOptionalWrapperFollowAppearanceAndDisappearance() throws Exception {
        MountFactory factory = SpringModules.noConfig("optional-spring")
                .customizer(context -> context.registerBean(
                        "snapshot",
                        ProviderSnapshot.class,
                        () -> new ProviderSnapshot(
                                context.containsBean("valueProvider")
                                        ? context.getBean("valueProvider", Provider.class)
                                        : null,
                                (Optional<Provider>) context.getBean("wrappedProvider", Optional.class))))
                .optional("valueProvider", PROVIDER)
                .optionalAsOptional("wrappedProvider", WRAPPED_PROVIDER)
                .expose(PROVIDER_SNAPSHOT)
                .build();
        MountHandle handle =
                runtime.mount("optional-spring", factory);
        ServiceAssertions.assertActive(handle);
        ProviderSnapshot missing = runtime.root().view().require(PROVIDER_SNAPSHOT);
        assertNull(missing.value());
        assertTrue(missing.wrapped().isEmpty());

        ExternalProvider provider = new ExternalProvider("present");
        Publication<Provider> wrappedPublication =
                runtime.publish(WRAPPED_PROVIDER, provider).publication();
        Publication<Provider> publication =
                runtime.publish(PROVIDER, provider).publication();
        ServiceAssertions.assertActive(handle);
        ProviderSnapshot present = runtime.root().view().require(PROVIDER_SNAPSHOT);
        assertSame(provider, present.value());
        assertSame(provider, present.wrapped().orElseThrow());

        wrappedPublication.unpublish().awaitSettled(Duration.ofSeconds(10));
        publication.unpublish().awaitSettled(Duration.ofSeconds(10));
        ServiceAssertions.assertActive(handle);
        ProviderSnapshot absentAgain = runtime.root().view().require(PROVIDER_SNAPSHOT);
        assertNull(absentAgain.value());
        assertTrue(absentAgain.wrapped().isEmpty());
        assertEquals(0, provider.springDestroys.get());
    }

    @Test
    void typedConfigReconfiguresAndWrongRawConfigIsRejectedAsInvalidConfig()
            throws Exception {
        ComponentFactory<ModuleConfig> factory =
                SpringModules.typed("typed-spring", ModuleConfig.class)
                        .annotatedClasses(TypedConfigConfig.class)
                        .configNormalizer(config -> new ModuleConfig(
                                config.value().trim().toUpperCase()))
                        .expose(CONFIGURED)
                        .build();
        ConfiguredMountHandle<ModuleConfig> handle =
                runtime.mount("typed-spring", factory, new ModuleConfig(" one "));
        ServiceAssertions.assertActive(handle);
        assertEquals("ONE", runtime.root().view().require(CONFIGURED).value());

        handle.reconfigureAsync(new ModuleConfig(" two "))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        ServiceAssertions.assertActive(handle);
        assertEquals("TWO", runtime.root().view().require(CONFIGURED).value());

        @SuppressWarnings({"rawtypes", "unchecked"})
        ComponentFactory rawFactory = factory;
        TransactionRejectedException rejected = assertThrows(
                TransactionRejectedException.class,
                () -> runtime.advanced().transact(transaction -> transaction.mount(
                        runtime.root(), "typed-spring-bad", rawFactory, "wrong raw config")));
        assertEquals(DiagnosticCode.INVALID_CONFIG,
                rejected.diagnostics().getFirst().code());
        assertTrue(runtime.root().view().find(CONFIGURED).isPresent());
    }

    @Test
    void externalDisposableConfigIsNotDestroyedAcrossContextGenerations() throws Exception {
        DisposableModuleConfig first = new DisposableModuleConfig("one");
        DisposableModuleConfig second = new DisposableModuleConfig("two");
        ComponentFactory<DisposableModuleConfig> factory =
                SpringModules.typed("disposable-config", DisposableModuleConfig.class)
                        .annotatedClasses(DisposableTypedConfig.class)
                        .expose(CONFIGURED)
                        .build();
        ConfiguredMountHandle<DisposableModuleConfig> handle =
                runtime.mount("disposable-config", factory, first);
        ServiceAssertions.assertActive(handle);
        assertEquals("one", runtime.root().view().require(CONFIGURED).value());

        handle.reconfigureAsync(second).toCompletableFuture().get(10, TimeUnit.SECONDS);
        ServiceAssertions.assertActive(handle);
        assertEquals("two", runtime.root().view().require(CONFIGURED).value());

        handle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(0, first.destroys.get());
        assertEquals(0, second.destroys.get());
    }

    @Test
    void refreshFailureDestroysCreatedBeansAndPublishesNoOutput() throws Exception {
        MountFactory factory =
                SpringModules.noConfig("refresh-failure")
                        .annotatedClasses(RefreshFailureConfig.class)
                        .expose(REFRESH_OUTPUT, "boom")
                        .build();
        MountHandle handle = runtime.mount("refresh-failure", factory);

        ComponentState state = handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.FAILED, state);
        assertTrue(runtime.root().view().find(REFRESH_OUTPUT).isEmpty());

        CountingDisposable created = refreshDisposable.get();
        assertNotNull(created);
        assertEquals(1, created.closeCount.get());
    }

    @Test
    void customizerFailureDestroysManuallyRegisteredSingletonBeforeFailing()
            throws Exception {
        CountingDisposable disposable = new CountingDisposable();
        MountFactory factory =
                SpringModules.noConfig("customizer-failure")
                        .customizer(context -> {
                            org.springframework.beans.factory.support.RootBeanDefinition definition =
                                    new org.springframework.beans.factory.support.RootBeanDefinition(
                                            CountingDisposable.class);
                            definition.setInstanceSupplier(() -> disposable);
                            context.registerBeanDefinition("manual", definition);
                            context.getBeanFactory().getBean("manual", CountingDisposable.class);
                            throw new IllegalStateException("customizer failed");
                        })
                        .build();
        MountHandle handle = runtime.mount("customizer-failure", factory);

        ComponentState state = handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.FAILED, state);
        assertEquals(1, disposable.closeCount.get());
    }

    @Test
    void inactiveCustomizerFailureRunsCustomHookThenPhysicalSingletonDestruction()
            throws Exception {
        CountingDisposable disposable = new CountingDisposable();
        AtomicReference<ClassLoader> hookLoader = new AtomicReference<>();
        MountFactory factory =
                SpringModules.noConfig("inactive-custom-hook")
                        .customizer(context -> {
                            org.springframework.beans.factory.support.RootBeanDefinition definition =
                                    new org.springframework.beans.factory.support.RootBeanDefinition(
                                            CountingDisposable.class);
                            definition.setInstanceSupplier(() -> disposable);
                            context.registerBeanDefinition("manual", definition);
                            context.getBeanFactory().getBean("manual", CountingDisposable.class);
                            throw new IllegalStateException("customizer failed");
                        })
                        .closer(context -> {
                            hookLoader.set(Thread.currentThread().getContextClassLoader());
                            return CompletableFuture.completedFuture(null);
                        })
                        .build();
        MountHandle handle = runtime.mount("inactive-custom-hook", factory);

        ComponentState state = handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.FAILED, state);
        assertEquals(1, disposable.closeCount.get());
        assertEquals(SpringModuleTest.class.getClassLoader(), hookLoader.get());
    }

    @Test
    void multipleOutputsArePublishedFromNamedSpringBeans() throws Exception {
        MountFactory factory = SpringModules.noConfig("multi-output")
                .annotatedClasses(MultiOutputConfig.class)
                .expose(FIRST, "first")
                .expose(SECOND, "second")
                .build();
        MountHandle handle = runtime.mount("multi-output", factory);
        ServiceAssertions.assertActive(handle);
        assertEquals("first-value", runtime.root().view().require(FIRST));
        assertEquals(42, runtime.root().view().require(SECOND));
    }

    @Test
    void failedCustomHookLeavesContextOpenThenRetryRunsPhysicalClose() throws Exception {
        internalDestroyLoader.set(null);
        ClassLoader expectedLoader = SpringModuleTest.class.getClassLoader();
        AtomicInteger attempts = new AtomicInteger();
        SpringContextCloser closer = context -> {
            if (attempts.incrementAndGet() == 1) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("temporary close failure"));
            }
            return CompletableFuture.completedFuture(null);
        };
        MountFactory factory = SpringModules.noConfig("closer-retry")
                .annotatedClasses(LoaderConfig.class)
                .closer(closer)
                .build();
        MountHandle handle = runtime.mount("closer-retry", factory);
        ServiceAssertions.assertActive(handle);

        ComponentState failed = handle.disposeAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.FAILED, failed);
        assertEquals(1, attempts.get());
        assertNull(internalDestroyLoader.get());

        ComponentState disposed = handle.retryAsync()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.DISPOSED, disposed);
        assertEquals(2, attempts.get());
        assertEquals(expectedLoader, internalDestroyLoader.get());
    }

    @Test
    void defaultLoaderUsesAnnotatedClassLoaderForStartRefreshAndCleanup() throws Exception {
        internalDestroyLoader.set(null);
        ClassLoader expectedLoader = SpringModuleTest.class.getClassLoader();
        AtomicReference<ClassLoader> customizerLoader = new AtomicReference<>();
        AtomicReference<ClassLoader> hookLoader = new AtomicReference<>();
        ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader unrelatedLoader = new ClassLoader(null) {
        };
        Thread.currentThread().setContextClassLoader(unrelatedLoader);

        try {
            MountFactory factory = SpringModules.noConfig("loader-default")
                    .annotatedClasses(LoaderConfig.class, MultiOutputConfig.class)
                    .customizer(context -> customizerLoader.set(
                            Thread.currentThread().getContextClassLoader()))
                    .closer(context -> {
                        hookLoader.set(Thread.currentThread().getContextClassLoader());
                        return CompletableFuture.completedFuture(null);
                    })
                    .expose(LOADER_SNAPSHOT)
                    .build();
            MountHandle handle = runtime.mount("loader-default", factory);
            ServiceAssertions.assertActive(handle);

            assertEquals(expectedLoader, customizerLoader.get());
            assertEquals(expectedLoader,
                    runtime.root().view().require(LOADER_SNAPSHOT).startLoader());
            assertEquals(unrelatedLoader, Thread.currentThread().getContextClassLoader());

            handle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(expectedLoader, hookLoader.get());
            assertEquals(expectedLoader, internalDestroyLoader.get());
            assertEquals(unrelatedLoader, Thread.currentThread().getContextClassLoader());
        } finally {
            Thread.currentThread().setContextClassLoader(originalLoader);
        }
    }

    @Test
    void annotatedClassesFromDifferentLoadersAreRejectedAtBuildTime() throws Exception {
        Class<?> copied = copyClassInIsolatedLoader(
                "io.knotra.spring.SpringModuleTest$DifferentLoaderConfig");
        assertNotEquals(DifferentLoaderConfig.class.getClassLoader(), copied.getClassLoader());

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () ->
                SpringModules.noConfig("mixed-loaders")
                        .annotatedClasses(DifferentLoaderConfig.class, copied)
                        .build());
        assertTrue(rejected.getMessage().contains("multiple class loaders"));
    }

    @Test
    void beanNameOwnershipIsCheckedRegardlessOfDeclarationOrder() {
        IllegalArgumentException outputFirst = assertThrows(
                IllegalArgumentException.class, () -> SpringModules.noConfig("bean-conflict")
                        .expose(FIRST, "shared-bean")
                        .required("shared-bean", SECOND));
        assertTrue(outputFirst.getMessage().contains("already used by an output"));

        IllegalArgumentException configFirst = assertThrows(
                IllegalArgumentException.class, () -> SpringModules.typed(
                                "typed-bean-conflict", ModuleConfig.class)
                        .required("knotraConfig", SECOND));
        assertTrue(configFirst.getMessage().contains(
                "config bean name is already used by a dependency"));

        IllegalArgumentException outputBeforeRename = assertThrows(
                IllegalArgumentException.class, () -> SpringModules.typed(
                                "typed-rename-conflict", ModuleConfig.class)
                        .expose(FIRST, "renamed-config")
                        .configBeanName("renamed-config"));
        assertTrue(outputBeforeRename.getMessage().contains(
                "config bean name is already used by an output"));
    }

    @Test
    void dependenciesKeepDeclarationOrderInDefinition() {
        ComponentFactory<ModuleConfig> factory = SpringModules.typed(
                        "dependency-order", ModuleConfig.class)
                .required("first-declared", FIRST)
                .required("second-declared", SECOND)
                .build();

        SpringModuleDefinition<ModuleConfig> definition =
                (SpringModuleDefinition<ModuleConfig>) factory;
        assertEquals(List.of("first-declared", "second-declared"),
                definition.dependencies().stream()
                        .map(SpringDependency::beanName)
                        .toList());
    }

    private static Class<?> copyClassInIsolatedLoader(String binaryName) throws IOException {
        String resource = "/" + binaryName.replace('.', '/') + ".class";
        byte[] bytecode;
        try (InputStream input = SpringModuleTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            bytecode = input.readAllBytes();
        }
        return new IsolatingClassLoader(SpringModuleTest.class.getClassLoader())
                .defineExisting(binaryName, bytecode);
    }

    private static final class IsolatingClassLoader extends ClassLoader {
        private IsolatingClassLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> defineExisting(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }

    private static ServiceSnapshot settleActive(MountHandle handle) throws Exception {
        ServiceAssertions.assertActive(handle);
        return null;
    }

    private static final class ServiceAssertions {
        private static void assertActive(MountHandle handle) throws Exception {
            ComponentState state = handle.whenSettled()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ComponentState.ACTIVE, state, () -> handle.componentId());
        }
    }
}
