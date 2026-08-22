package io.knotra.spring;

import io.knotra.CapabilityKey;
import io.knotra.ComponentFactory;
import io.knotra.ComponentHandle;
import io.knotra.ComponentState;
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

    @Configuration
    static class DynamicConfig {
        @Bean
        ApiSnapshot snapshot(@Qualifier("api") Api api) {
            return new ApiSnapshot(api);
        }
    }

    @Test
    void dynamicProviderReplacementDoesNotRebuildSpringContext() throws Exception {
        RegistrationHandle first = runtime.provide(API, new ApiValue("v1"));
        AtomicInteger contexts = new AtomicInteger();

        ComponentFactory<NoConfig> factory = SpringModules.noConfig("dynamic-child")
                .annotatedClasses(DynamicConfig.class)
                .customizer(context -> contexts.incrementAndGet())
                .dynamic("api", API)
                .expose(API_SNAPSHOT)
                .build();
        ComponentHandle<NoConfig> handle = runtime.mount("dynamic-child", factory);
        assertActive(handle);
        ApiSnapshot firstSnapshot = runtime.root().view().require(API_SNAPSHOT);
        assertEquals("v1", firstSnapshot.value());
        assertEquals(1, contexts.get());

        runtime.transact(transaction -> {
            transaction.revoke(first);
            transaction.provide(runtime.root(), API, new ApiValue("v2"));
            return null;
        }).settlement().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertActive(handle);
        ApiSnapshot secondSnapshot = runtime.root().view().require(API_SNAPSHOT);
        assertSame(firstSnapshot, secondSnapshot);
        assertEquals("v2", secondSnapshot.value());
        assertEquals(1, contexts.get());

        handle.disposeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static void assertActive(ComponentHandle<?> handle) throws Exception {
        ComponentState state = handle.whenSettled()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(ComponentState.ACTIVE, state, () -> handle.componentId());
    }
}
