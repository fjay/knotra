package io.knotra;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

final class ApiSurfaceTest {
    @Test
    void runtimeImplementationIsNotDirectlyInstantiable() throws Exception {
        Class<?> runtimeClass = Class.forName(
                "io.knotra.internal.DefaultKnotraRuntime");
        assertFalse(Modifier.isPublic(runtimeClass.getModifiers()));

        Constructor<?> constructor = runtimeClass.getDeclaredConstructor(KnotraConfig.class);
        assertFalse(Modifier.isPublic(constructor.getModifiers()));
        assertThrows(IllegalAccessException.class,
                () -> constructor.newInstance(KnotraConfig.defaults()));
    }

    @Test
    void internalRuntimeBootstrapOnlyExposesInterfaceFactory() throws Exception {
        Class<?> bootstrap = Class.forName("io.knotra.internal.RuntimeBootstrap");
        assertTrue(Modifier.isPublic(bootstrap.getModifiers()));

        Constructor<?> constructor = bootstrap.getDeclaredConstructor();
        assertFalse(Modifier.isPublic(constructor.getModifiers()));
        assertThrows(IllegalAccessException.class, constructor::newInstance);

        assertEquals(1, bootstrap.getDeclaredMethods().length);
        java.lang.reflect.Method create = bootstrap.getDeclaredMethods()[0];
        assertEquals("create", create.getName());
        assertTrue(Modifier.isStatic(create.getModifiers()));
        assertTrue(Modifier.isPublic(create.getModifiers()));
        assertEquals(KnotraRuntime.class, create.getReturnType());
    }

    @Test
    void simpleRuntimeHidesAdvancedStructuralOperations() {
        assertTrue(Arrays.stream(KnotraRuntime.class.getMethods())
                .noneMatch(method -> method.getName().equals("transact")
                        || method.getName().equals("snapshot")
                        || method.getName().equals("register")
                        || method.getName().equals("revoke")));
        assertTrue(Arrays.stream(KnotraRuntime.class.getMethods())
                .anyMatch(method -> method.getName().equals("advanced")));
        assertTrue(Arrays.stream(KnotraRuntime.class.getMethods())
                .anyMatch(method -> method.getName().equals("require")
                        && Arrays.equals(method.getParameterTypes(), new Class<?>[]{Class.class})));
        assertTrue(Arrays.stream(KnotraRuntime.class.getMethods())
                .anyMatch(method -> method.getName().equals("find")
                        && Arrays.equals(method.getParameterTypes(), new Class<?>[]{Class.class})));
        assertTrue(Arrays.stream(Publication.class.getMethods())
                .noneMatch(method -> method.getName().equals("currentRegistration")
                        || method.getName().equals("currentInternal")
                        || method.getName().equals("slotId")));
        assertTrue(Arrays.stream(PublicationChange.class.getMethods())
                .noneMatch(method -> method.getName().equals("registration")));
        // 公共 Registration 类型已删除：Publication 是注册演进的唯一稳定入口。
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.knotra.Registration"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.knotra.internal.RegistrationImpl"));
        assertTrue(Arrays.stream(SettlementReport.class.getMethods())
                .noneMatch(method -> method.getName().equals("allAffectedActive")));
        assertTrue(Arrays.stream(SettlementReport.class.getMethods())
                .anyMatch(method -> method.getName().equals("hasAffectedMounts")));
        assertTrue(Arrays.stream(SettlementReport.class.getMethods())
                .noneMatch(method -> method.getName().equals("allActive")));
        Arrays.stream(KnotraRuntime.class.getMethods())
                .filter(method -> method.getName().equals("mount")
                        && method.getParameterTypes().length >= 2
                        && method.getParameterTypes()[1] == MountFactory.class)
                .forEach(method -> {
                    assertTrue(Arrays.stream(method.getParameterTypes())
                            .anyMatch(MountFactory.class::equals),
                            method.toString());
                    assertTrue(Arrays.stream(method.getParameterTypes())
                            .noneMatch(type -> type == NoConfig.class),
                            method.toString());
                    assertTrue(Arrays.stream(method.getGenericParameterTypes())
                            .noneMatch(type -> type.getTypeName().contains("NoConfig")),
                            method.toString());
                });
    }


    @Test
    void mountHandleSplitsPlainMountsFromConfiguredMounts() {
        assertTrue(Arrays.stream(MountHandle.class.getMethods())
                .noneMatch(method -> method.getName().startsWith("reconfigure")));
        assertTrue(Arrays.stream(ConfiguredMountHandle.class.getMethods())
                .anyMatch(method -> method.getName().equals("reconfigureAsync")));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.knotra.ComponentHandle"));
    }

    @Test
    void runtimeSnapshotUsesMountTerminology() {
        assertTrue(Arrays.stream(RuntimeSnapshot.class.getMethods())
                .anyMatch(method -> method.getName().equals("mounts")));
        assertTrue(Arrays.stream(RuntimeSnapshot.class.getMethods())
                .noneMatch(method -> method.getName().equals("components")));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.knotra.RuntimeSnapshot$ComponentSnapshot"));
    }

    @Test
    void classShortcutsReachContextAndActivationViews() {
        KnotraRuntime runtime = KnotraRuntime.create();
        try {
            runtime.publish(String.class, "one");
            assertEquals("one", runtime.root().view().require(String.class));
            assertTrue(runtime.root().view().find(String.class).isPresent());

            ContextHandle child = runtime.advanced().childContext(runtime.root(), "child");
            runtime.publish(child, Greeting.class, new EnglishGreeting());
            Greeting greeting = child.view().require(Greeting.class);
            assertEquals("hello", greeting.say());

            MountHandle provider = runtime.mount("provider",
                    TestKit.mountFactory("provider", new TestKit.Scripted<>(
                            ComponentDescriptor.named("provider"),
                            (context, config) -> context.provide(Farewell.class, new ShortFarewell()))));
            MountHandle consumer = runtime.mount("consumer",
                    TestKit.mountFactory("consumer", new TestKit.Scripted<>(
                            ComponentDescriptor.named("consumer",
                                    CapabilityRequirement.required(
                                            CapabilityKey.of(Farewell.class))),
                            (context, config) -> assertEquals("bye",
                                    context.require(Farewell.class).farewell()))));
            provider.requireActive(java.time.Duration.ofSeconds(5));
            assertFalse(provider instanceof ConfiguredMountHandle<?>);
            assertFalse(consumer instanceof ConfiguredMountHandle<?>);
            ConfiguredMountHandle<String> configured = runtime.mount(
                    "configured-provider",
                    TestKit.factory("configured-provider", new TestKit.Scripted<>(
                            ComponentDescriptor.named("configured-provider"),
                            (context, config) -> assertEquals("value", config))),
                    "value");
            assertTrue(configured instanceof ConfiguredMountHandle<?>);
            configured.requireActive(java.time.Duration.ofSeconds(5));
            consumer.requireActive(java.time.Duration.ofSeconds(5));
        } finally {
            runtime.close();
        }
    }

    interface Greeting {
        String say();
    }

    record EnglishGreeting() implements Greeting {
        @Override
        public String say() {
            return "hello";
        }
    }

    interface Farewell {
        String farewell();
    }

    record ShortFarewell() implements Farewell {
        @Override
        public String farewell() {
            return "bye";
        }
    }
}
