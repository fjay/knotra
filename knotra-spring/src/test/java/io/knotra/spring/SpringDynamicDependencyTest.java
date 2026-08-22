package io.knotra.spring;

import io.knotra.CapabilityKey;
import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ComponentState;
import io.knotra.DynamicCapability;
import io.knotra.KnotraRuntime;
import io.knotra.NoConfig;
import io.knotra.RegistrationHandle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class SpringDynamicDependencyTest {

    static final CapabilityKey<Api> API = CapabilityKey.of("spring-dynamic.api", Api.class);
    static final CapabilityKey<ApiSnapshot> API_SNAPSHOT =
            CapabilityKey.of("spring-dynamic.api-snapshot", ApiSnapshot.class);
    static final CapabilityKey<CapabilitySnapshot> CAPABILITY_SNAPSHOT =
            CapabilityKey.of(
                    "spring-dynamic.capability-snapshot",
            CapabilitySnapshot.class);

    KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    interface Api {
        String value();
    }

    record ApiValue(String value) implements Api {
    }

    record ApiSnapshot(Api api) {
        String value() {
            return api.value();
        }
    }

    record CapabilitySnapshot(DynamicCapability<Api> api) {
        String value() {
            return api.call(Api::value);
        }
    }

    @Configuration
    static class DynamicProxyConfig {
        @Bean
        ApiSnapshot snapshot(@Qualifier("api") Api api) {
            return new ApiSnapshot(api);
        }
    }

    @Configuration
    static class DynamicCapabilityConfig {
        @Bean
        CapabilitySnapshot snapshot(
                @Qualifier("api") DynamicCapability<Api> api) {
            return new CapabilitySnapshot(api);
        }
    }

    @Test
    void dynamicProxyProviderReplacementDoesNotRebuildSpringContext() throws Exception {
        RegistrationHandle first = runtime.provide(API, new ApiValue("v1"));
        AtomicInteger contexts = new AtomicInteger();

        ComponentFactory<NoConfig> factory = SpringModules.noConfig("dynamic-proxy-child")
                .annotatedClasses(DynamicProxyConfig.class)
                .customizer(context -> contexts.incrementAndGet())
                .dynamicProxyRequired("api", API)
                .expose(API_SNAPSHOT)
                .build();
        ComponentHandle<NoConfig> handle = runtime.mount("dynamic-proxy-child", factory);
        assertActive(handle);
        ApiSnapshot firstSnapshot = runtime.root().view().require(API_SNAPSHOT);
        assertEquals("v1", firstSnapshot.value());
        assertEquals(1, contexts.get());

        replaceProvider(first, "v2");

        assertActive(handle);
        ApiSnapshot secondSnapshot = runtime.root().view().require(API_SNAPSHOT);
        assertSame(firstSnapshot, secondSnapshot);
        assertEquals("v2", secondSnapshot.value());
        assertEquals(1, contexts.get());

        handle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void dynamicCapabilityProvidesProviderFixedCallbackAcrossReplacement() throws Exception {
        RegistrationHandle first = runtime.provide(API, new ApiValue("v1"));
        AtomicInteger contexts = new AtomicInteger();

        ComponentFactory<NoConfig> factory =
                SpringModules.noConfig("dynamic-capability-child")
                        .annotatedClasses(DynamicCapabilityConfig.class)
                        .customizer(context -> contexts.incrementAndGet())
                        .dynamicRequired("api", API)
                        .expose(CAPABILITY_SNAPSHOT)
                        .build();
        ComponentHandle<NoConfig> handle =
                runtime.mount("dynamic-capability-child", factory);
        assertActive(handle);
        CapabilitySnapshot snapshot =
                runtime.root().view().require(CAPABILITY_SNAPSHOT);
        assertEquals("v1", snapshot.value());

        replaceProvider(first, "v2");

        assertActive(handle);
        assertSame(snapshot, runtime.root().view().require(CAPABILITY_SNAPSHOT));
        assertEquals("v2", snapshot.value());
        assertEquals(1, contexts.get());

        handle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void dynamicOptionalDependenciesStartMissingThenFollowAppearance() throws Exception {
        AtomicInteger capabilityContexts = new AtomicInteger();
        AtomicInteger proxyContexts = new AtomicInteger();

        ComponentFactory<NoConfig> capabilityFactory =
                SpringModules.noConfig("dynamic-capability-optional-child")
                        .annotatedClasses(DynamicCapabilityConfig.class)
                        .customizer(context -> capabilityContexts.incrementAndGet())
                        .dynamicOptional("api", API)
                        .expose(CAPABILITY_SNAPSHOT)
                        .build();
        ComponentHandle<NoConfig> capabilityHandle =
                runtime.mount("dynamic-capability-optional-child", capabilityFactory);
        assertActive(capabilityHandle);
        CapabilitySnapshot missingCapability =
                runtime.root().view().require(CAPABILITY_SNAPSHOT);
        assertFalse(missingCapability.api().available());

        ComponentFactory<NoConfig> proxyFactory =
                SpringModules.noConfig("dynamic-proxy-optional-child")
                        .annotatedClasses(DynamicProxyConfig.class)
                        .customizer(context -> proxyContexts.incrementAndGet())
                        .dynamicProxyOptional("api", API)
                        .expose(API_SNAPSHOT)
                        .build();
        ComponentHandle<NoConfig> proxyHandle =
                runtime.mount("dynamic-proxy-optional-child", proxyFactory);
        assertActive(proxyHandle);
        ApiSnapshot missingProxy = runtime.root().view().require(API_SNAPSHOT);

        runtime.provide(API, new ApiValue("v1"));

        assertActive(capabilityHandle);
        assertActive(proxyHandle);
        assertTrue(missingCapability.api().available());
        assertEquals("v1", missingCapability.value());
        assertSame(missingProxy, runtime.root().view().require(API_SNAPSHOT));
        assertEquals("v1", missingProxy.value());
        assertEquals(1, capabilityContexts.get());
        assertEquals(1, proxyContexts.get());

        capabilityHandle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        proxyHandle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private void replaceProvider(RegistrationHandle previous, String value)
            throws Exception {
        runtime.transact(transaction -> {
            transaction.revoke(previous);
            transaction.provide(runtime.root(), API, new ApiValue(value));
            return null;
        }).settlement().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static void assertActive(ComponentHandle<?> handle) throws Exception {
        ComponentState state = handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.ACTIVE, state, () -> handle.componentId());
    }
}
