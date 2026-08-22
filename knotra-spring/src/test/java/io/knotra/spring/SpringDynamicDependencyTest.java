package io.knotra.spring;

import io.knotra.CapabilityKey;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.ComponentState;
import io.knotra.DynamicCapability;
import io.knotra.KnotraRuntime;
import io.knotra.Publication;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
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
    void dynamicProviderReplacementDoesNotRebuildSpringContext() throws Exception {
        Publication<Api> publication = runtime.publish(API, new ApiValue("v1"))
                .publication();
        AtomicInteger contexts = new AtomicInteger();

        MountFactory factory = SpringModules.noConfig("dynamic-proxy-child")
                .annotatedClasses(DynamicProxyConfig.class)
                .customizer(context -> contexts.incrementAndGet())
                .dynamic("api", API)
                .expose(API_SNAPSHOT)
                .build();
        MountHandle handle = runtime.mount("dynamic-proxy-child", factory);
        assertActive(handle);
        ApiSnapshot firstSnapshot = runtime.root().view().require(API_SNAPSHOT);
        assertEquals("v1", firstSnapshot.value());
        assertEquals(1, contexts.get());

        publication.update(new ApiValue("v2")).awaitSettled(Duration.ofSeconds(10));

        assertActive(handle);
        ApiSnapshot secondSnapshot = runtime.root().view().require(API_SNAPSHOT);
        assertSame(firstSnapshot, secondSnapshot);
        assertEquals("v2", secondSnapshot.value());
        assertEquals(1, contexts.get());

        handle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void dynamicDeclarationsAcceptInterfaceCapability() {
        assertNotNull(SpringModules.noConfig("proxy-iface-required")
                .dynamic("api", API));
        assertNotNull(SpringModules.noConfig("proxy-iface-class-required")
                .dynamic("api", Api.class));
        assertNotNull(SpringModules.noConfig("proxy-iface-optional")
                .dynamicOptional("api", API));
    }

    @Test
    void dynamicRejectsNonInterfaceCapabilityAtDeclaration() {
        CapabilityKey<ApiValue> key =
                CapabilityKey.of("spring-dynamic.api-value-required", ApiValue.class);

        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> SpringModules.noConfig("proxy-class-required")
                        .dynamic("api", key));
        assertTrue(rejected.getMessage().contains("must be an interface"));
        assertTrue(rejected.getMessage().contains(ApiValue.class.getName()));
    }

    @Test
    void dynamicOptionalRejectsNonInterfaceCapabilityAtDeclaration() {
        CapabilityKey<ApiValue> key =
                CapabilityKey.of("spring-dynamic.api-value-optional", ApiValue.class);

        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> SpringModules.noConfig("proxy-class-optional")
                        .dynamicOptional("api", key));
        assertTrue(rejected.getMessage().contains("must be an interface"));
        assertTrue(rejected.getMessage().contains(ApiValue.class.getName()));
    }

    @Test
    void dynamicCapabilityProvidesProviderFixedCallbackAcrossReplacement() throws Exception {
        Publication<Api> publication = runtime.publish(API, new ApiValue("v1"))
                .publication();
        AtomicInteger contexts = new AtomicInteger();

        MountFactory factory =
                SpringModules.noConfig("dynamic-capability-child")
                        .annotatedClasses(DynamicCapabilityConfig.class)
                        .customizer(context -> contexts.incrementAndGet())
                        .dynamicCapability("api", API)
                        .expose(CAPABILITY_SNAPSHOT)
                        .build();
        MountHandle handle =
                runtime.mount("dynamic-capability-child", factory);
        assertActive(handle);
        CapabilitySnapshot firstSnapshot =
                runtime.root().view().require(CAPABILITY_SNAPSHOT);
        assertEquals("v1", firstSnapshot.value());
        assertEquals(1, contexts.get());

        publication.update(new ApiValue("v2")).awaitSettled(Duration.ofSeconds(10));

        assertActive(handle);
        CapabilitySnapshot secondSnapshot =
                runtime.root().view().require(CAPABILITY_SNAPSHOT);
        assertSame(firstSnapshot, secondSnapshot);
        assertEquals("v2", secondSnapshot.value());
        assertEquals(1, contexts.get());

        handle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void dynamicOptionalDependenciesStartMissingThenFollowAppearance() throws Exception {
        AtomicInteger capabilityContexts = new AtomicInteger();
        AtomicInteger proxyContexts = new AtomicInteger();

        MountFactory capabilityFactory =
                SpringModules.noConfig("dynamic-capability-optional-child")
                        .annotatedClasses(DynamicCapabilityConfig.class)
                        .customizer(context -> capabilityContexts.incrementAndGet())
                        .dynamicCapabilityOptional("api", API)
                        .expose(CAPABILITY_SNAPSHOT)
                        .build();
        MountHandle capabilityHandle =
                runtime.mount("dynamic-capability-optional-child", capabilityFactory);
        assertActive(capabilityHandle);
        CapabilitySnapshot missingCapability =
                runtime.root().view().require(CAPABILITY_SNAPSHOT);
        assertFalse(missingCapability.api().available());

        MountFactory proxyFactory =
                SpringModules.noConfig("dynamic-proxy-optional-child")
                        .annotatedClasses(DynamicProxyConfig.class)
                        .customizer(context -> proxyContexts.incrementAndGet())
                        .dynamicOptional("api", API)
                        .expose(API_SNAPSHOT)
                        .build();
        MountHandle proxyHandle =
                runtime.mount("dynamic-proxy-optional-child", proxyFactory);
        assertActive(proxyHandle);
        ApiSnapshot missingProxy = runtime.root().view().require(API_SNAPSHOT);

        runtime.publish(API, new ApiValue("v1")).awaitSettled(Duration.ofSeconds(10));

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

    private static void assertActive(MountHandle handle) throws Exception {
        ComponentState state = handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.ACTIVE, state, () -> handle.componentId());
    }
}

