package io.knotra.internal;

import io.knotra.ActivationState;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentOrigin;
import io.knotra.ComponentState;
import io.knotra.ContextState;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeGraphTest {
    private static final CapabilityKey<String> CAP = CapabilityKey.of("cap", String.class);

    @Test
    void contextShadowingAndTentativeOverlayFollowNearestContextWins() {
        RuntimeView.Draft draft = draft();
        RuntimeView.RegistrationData parent = registration("parent", CAP, "ctx-root", "act-parent");
        RuntimeView.RegistrationData committedChild =
                registration("committed-child", CAP, "ctx-child", "act-child-provider");
        RuntimeView.RegistrationData tentativeChild =
                registration("tentative-child", CAP, "ctx-child", "act-child-provider");
        draft.registrations.put("parent", parent);
        draft.registrations.put("committed-child", committedChild);

        assertEquals(Optional.of(parent), draft.resolve("ctx-root", CAP));
        assertEquals(Optional.of(committedChild), draft.resolve("ctx-child", CAP));

        Map<String, RuntimeView.RegistrationData> childOverlay =
                Map.of("tentative-child", tentativeChild);
        RuntimeGraph graph = RuntimeGraph.of(draft, childOverlay);
        assertEquals(Optional.of(tentativeChild),
                graph.resolve(draft, childOverlay, "ctx-child", CAP));
        assertEquals(Optional.of(parent),
                graph.resolve(draft, childOverlay, "ctx-root", CAP));

        RuntimeView.RegistrationData tentativeParent =
                registration("tentative-parent", CAP, "ctx-root", "act-parent");
        Map<String, RuntimeView.RegistrationData> overlay = Map.of(
                "tentative-parent", tentativeParent,
                "tentative-child", tentativeChild);
        graph = RuntimeGraph.of(draft, overlay);
        assertEquals(Optional.of(tentativeChild),
                graph.resolve(draft, overlay, "ctx-child", CAP));
        assertEquals(Optional.of(tentativeParent),
                graph.resolve(draft, overlay, "ctx-root", CAP));

        CapabilityKey<?> stringType = CapabilityKey.of("typed", String.class);
        CapabilityKey<?> characterType = CapabilityKey.of("typed", Character.class);
        RuntimeView.RegistrationData stringRegistration =
                registration("typed-string", stringType, "ctx-root", null);
        RuntimeView.RegistrationData characterRegistration =
                registration("typed-character", characterType, "ctx-root", null);
        Map<String, RuntimeView.RegistrationData> typedOverlay = new LinkedHashMap<>();
        typedOverlay.put("typed-string", stringRegistration);
        typedOverlay.put("typed-character", characterRegistration);
        graph = RuntimeGraph.of(draft, typedOverlay);
        assertEquals(Optional.of(stringRegistration),
                graph.resolve(draft, typedOverlay, "ctx-root", stringType));
        assertEquals(Optional.of(characterRegistration),
                graph.resolve(draft, typedOverlay, "ctx-root", characterType));
        assertEquals("/root/child", graph.canonicalPath(draft, "ctx-child"));
    }

    @Test
    void effectiveBindingsKeepDynamicDetachedAndFixedBoundIdentity() {
        RuntimeView.Draft draft = draft();
        RuntimeView.RegistrationData provider =
                registration("provider", CAP, "ctx-root", "act-provider");
        draft.registrations.put("provider", provider);
        ComponentDescriptor dynamic = ComponentDescriptor.named(
                "dynamic", CapabilityRequirement.dynamicRequired(CAP));
        ComponentDescriptor fixed = ComponentDescriptor.named(
                "fixed", CapabilityRequirement.required(CAP));
        draft.components.put("dynamic-consumer", component(
                "dynamic-consumer", "ctx-root", dynamic, "act-dynamic", ActivationState.ACTIVE,
                Map.of()));
        draft.components.put("fixed-consumer", component(
                "fixed-consumer", "ctx-root", fixed, "act-fixed", ActivationState.ACTIVE,
                Map.of("cap", new RuntimeView.BindingData(
                        "provider", true,
                        CapabilityRequirement.Mode.REQUIRED,
                        CapabilityRequirement.CapabilityBinding.PINNED))));

        RuntimeGraph graph = draft.graph();
        Map<String, RuntimeView.BindingData> dynamicBindings =
                graph.effectiveBindings(
                        draft, Map.of(), draft.components.get("dynamic-consumer"));
        Map<String, RuntimeView.BindingData> fixedBindings =
                graph.effectiveBindings(
                        draft, Map.of(), draft.components.get("fixed-consumer"));

        assertEquals(new RuntimeView.BindingData(
                null, false, CapabilityRequirement.Mode.REQUIRED,
                CapabilityRequirement.CapabilityBinding.DYNAMIC), dynamicBindings.get("cap"));
        assertEquals(new RuntimeView.BindingData(
                "provider", true, CapabilityRequirement.Mode.REQUIRED,
                CapabilityRequirement.CapabilityBinding.PINNED), fixedBindings.get("cap"));
    }

    @Test
    void dependencyGraphUsesOwnershipFiltersAndDetectsCycle() {
        RuntimeView.Draft draft = draft();
        RuntimeView.RegistrationData aProvider =
                registration("a-provider", CAP, "ctx-root", "act-a");
        draft.registrations.put("a-provider", aProvider);
        CapabilityKey<String> other = CapabilityKey.of("other", String.class);
        RuntimeView.RegistrationData bProvider =
                registration("b-provider", other, "ctx-root", "act-b");
        draft.registrations.put("b-provider", bProvider);
        draft.activations.put("act-a", activation("act-a", "a", Map.of()));
        draft.activations.put("act-b", activation("act-b", "b",
                Map.of("cap", binding("a-provider"))));
        draft.activations.put("act-stopping",
                activation("act-stopping", "stopping", Map.of())
                        .withState(ActivationState.STOPPING));

        draft.components.put("a", component("a", "ctx-root",
                ComponentDescriptor.named("a", CapabilityRequirement.dynamicRequired(other)),
                "act-a", ActivationState.ACTIVE, Map.of()));
        draft.components.put("b", component("b", "ctx-root",
                ComponentDescriptor.named("b", CapabilityRequirement.required(CAP)),
                "act-b", ActivationState.ACTIVE,
                Map.of("cap", binding("a-provider"))));
        draft.components.put("stopping", component("stopping", "ctx-root",
                ComponentDescriptor.named("stopping", CapabilityRequirement.dynamicRequired(CAP)),
                "act-stopping", ActivationState.STOPPING, Map.of()));

        Map<String, Set<String>> graph =
                draft.graph().dependencyGraph(draft, Map.of());
        assertEquals(Set.of("b"), graph.get("a"));
        assertEquals(Set.of("a"), graph.get("b"));
        assertFalse(graph.containsKey("stopping"));
        assertTrue(RuntimeGraph.hasCycle(graph));

        Set<String> closure = draft.graph().dependentsClosure(draft, Set.of("a"));
        assertEquals(Set.of("a", "b"), closure);

        draft.activations.put("act-a",
                draft.activations.get("act-a").detached());
        Map<String, Set<String>> detached =
                draft.graph().dependencyGraph(draft, Map.of());
        assertFalse(detached.containsKey("a"));
        assertFalse(RuntimeGraph.hasCycle(detached));
    }

    @Test
    void ownershipAndRegistrationFiltersUseActivationOwnership() {
        RuntimeView.Draft draft = draft();
        draft.components.put("parent", component("parent", "ctx-root",
                ComponentDescriptor.named("parent"), "act-parent",
                ActivationState.ACTIVE, Map.of()));
        draft.components.put("child", ownershipChild(
                "child", "parent", "act-parent", "act-child"));
        draft.components.put("grandchild", ownershipChild(
                "grandchild", "child", "act-child", "act-grandchild"));
        draft.activations.put("act-parent", activation("act-parent", "parent", Map.of()));
        draft.activations.put("act-child", activation("act-child", "child", Map.of()));
        draft.activations.put("act-grandchild",
                activation("act-grandchild", "grandchild", Map.of()));
        draft.registrations.put("parent-reg", registration("parent-reg", CAP, "ctx-root", "act-parent"));
        draft.registrations.put("child-reg", registration("child-reg", CAP, "ctx-root", "act-child"));
        draft.registrations.put("host-reg", registration("host-reg", CAP, "ctx-root", null));

        RuntimeGraph graph = draft.graph();
        assertEquals(Set.of("parent", "child", "grandchild"),
                graph.ownershipDescendants(draft, "parent"));
        assertEquals(Set.of("parent", "child", "grandchild"),
                graph.ownershipDescendantsForActivation(draft, "parent", "act-parent"));
        assertEquals(List.of("parent-reg"),
                graph.registrationsOwnedBy(draft, Set.of("parent")));
        assertEquals(List.of("child-reg", "parent-reg"),
                graph.registrationsOwnedBy(draft, Set.of("parent", "child")));
    }

    @Test
    void draftMutationProducesAFreshGraph() {
        RuntimeView.Draft draft = draft();
        draft.registrations.put("provider", registration("provider", CAP, "ctx-root", "act-provider"));
        RuntimeGraph before = draft.graph();
        assertTrue(before.resolve(draft, Map.of(), "ctx-root", CAP).isPresent());

        draft.registrations.remove("provider");
        RuntimeGraph after = draft.graph();
        assertTrue(before != after);
        assertFalse(after.resolve(draft, Map.of(), "ctx-root", CAP).isPresent());
    }

    @Test
    void dynamicTopologyEdgesKeepModeAndStableOwnerIdentity() {
        RuntimeView.Draft draft = draft();
        draft.registrations.put("host-provider",
                registration("host-provider", CAP, "ctx-root", null));
        draft.components.put("consumer", component(
                "consumer", "ctx-root",
                ComponentDescriptor.named("consumer", CapabilityRequirement.dynamicRequired(CAP)),
                "act-consumer", ActivationState.ACTIVE, Map.of()));
        draft.activations.put("act-consumer", activation("act-consumer", "consumer", Map.of()));

        RuntimeGraph hostGraph = draft.graph();
        RuntimeGraph.DynamicEdge hostEdge = new RuntimeGraph.DynamicEdge(
                "cap",
                new RuntimeGraph.ProviderOwnerIdentity(
                        RuntimeGraph.ProviderOwnerKind.HOST, "ctx-root"),
                CapabilityRequirement.Mode.REQUIRED);
        assertEquals(Set.of(hostEdge), hostGraph.dynamicDependencyEdges(draft));

        draft.components.put("consumer", component(
                "consumer", "ctx-root",
                ComponentDescriptor.named("consumer", CapabilityRequirement.dynamicOptional(CAP)),
                "act-consumer", ActivationState.ACTIVE, Map.of()));
        RuntimeGraph optionalGraph = draft.graph();
        assertFalse(hostGraph.dynamicDependencyEdges(draft).equals(
                optionalGraph.dynamicDependencyEdges(draft)),
                "OPTIONAL and REQUIRED must remain distinct topology edges");
        assertEquals(Set.of(new RuntimeGraph.DynamicEdge(
                "cap",
                new RuntimeGraph.ProviderOwnerIdentity(
                        RuntimeGraph.ProviderOwnerKind.HOST, "ctx-root"),
                CapabilityRequirement.Mode.OPTIONAL)),
                optionalGraph.dynamicDependencyEdges(draft));
    }

    @Test
    void dynamicTopologyIgnoresSameOwnerRegistrationReplacement() {
        RuntimeView.Draft draft = draft();
        draft.activations.put("act-provider-1", activation("act-provider-1", "provider", Map.of()));
        draft.activations.put("act-provider-2", activation("act-provider-2", "provider", Map.of()));
        draft.components.put("provider", component(
                "provider", "ctx-root",
                ComponentDescriptor.named("provider"),
                "act-provider-1", ActivationState.ACTIVE, Map.of()));
        draft.components.put("consumer", component(
                "consumer", "ctx-root",
                ComponentDescriptor.named("consumer", CapabilityRequirement.dynamicRequired(CAP)),
                "act-consumer", ActivationState.ACTIVE, Map.of()));
        draft.activations.put("act-consumer", activation("act-consumer", "consumer", Map.of()));
        draft.registrations.put("old-registration",
                registration("old-registration", CAP, "ctx-root", "act-provider-1"));

        Set<RuntimeGraph.DynamicEdge> before = draft.graph().dynamicDependencyEdges(draft);
        draft.registrations.remove("old-registration");
        draft.registrations.put("new-registration",
                registration("new-registration", CAP, "ctx-root", "act-provider-2"));
        assertEquals(before, draft.graph().dynamicDependencyEdges(draft));
        assertEquals(Set.of(new RuntimeGraph.DynamicEdge(
                "cap",
                new RuntimeGraph.ProviderOwnerIdentity(
                        RuntimeGraph.ProviderOwnerKind.ACTIVATION, "provider"),
                CapabilityRequirement.Mode.REQUIRED)),
                before);
    }

    @Test
    void tentativeDynamicRegistrationParticipatesInCycleDetection() {
        RuntimeView.Draft draft = draft();
        CapabilityKey<String> firstKey = CapabilityKey.of("first", String.class);
        CapabilityKey<String> secondKey = CapabilityKey.of("second", String.class);
        draft.registrations.put("first-reg",
                registration("first-reg", firstKey, "ctx-root", "act-first"));
        RuntimeView.RegistrationData staged =
                registration("staged-second", secondKey, "ctx-root", "act-second");
        draft.components.put("first", component(
                "first", "ctx-root",
                ComponentDescriptor.named("first", CapabilityRequirement.dynamicRequired(secondKey)),
                "act-first", ActivationState.ACTIVE, Map.of()));
        draft.components.put("second", component(
                "second", "ctx-root",
                ComponentDescriptor.named("second", CapabilityRequirement.required(firstKey)),
                "act-second", ActivationState.ACTIVE,
                Map.of("first", binding("first-reg"))));
        draft.activations.put("act-first", activation("act-first", "first", Map.of()));
        draft.activations.put("act-second", activation("act-second", "second",
                Map.of("first", binding("first-reg"))));

        Map<String, RuntimeView.RegistrationData> overlay = Map.of("second", staged);
        RuntimeGraph graph = RuntimeGraph.of(draft, overlay);
        Map<String, Set<String>> dependencies = graph.dependencyGraph(draft, overlay);
        assertEquals(Set.of("second"), dependencies.get("first"));
        assertEquals(Set.of("first"), dependencies.get("second"));
        assertTrue(RuntimeGraph.hasCycle(dependencies));
    }

    @Test
    void dependencyGraphMatchesReferenceAlgorithmOnDeterministicRandomGraphs() {
        Random random = new Random(0x5eedL);
        for (int round = 0; round < 200; round++) {
            RuntimeView.Draft draft = randomDraft(random, 4 + random.nextInt(8));
            assertEquals(referenceDependencyGraph(draft),
                    draft.graph().dependencyGraph(draft, Map.of()), "round " + round);
        }
    }

    @Test
    void indexedAndDirectResolutionAgreeOnDeterministicRandomGraphs() {
        Random random = new Random(0xC0FFEE5L);
        for (int round = 0; round < 300; round++) {
            DraftFixture fixture = randomDraftWithOverlay(random, 4 + random.nextInt(8));
            RuntimeGraph graph = RuntimeGraph.of(fixture.draft(), fixture.overlay());
            RuntimeGraph.ResolutionCache indexed = RuntimeGraph.resolutionCache();
            RuntimeGraph.ResolutionCache direct = RuntimeGraph.resolutionCache();

            Set<CapabilityKey<?>> keys = new LinkedHashSet<>();
            for (RuntimeView.ComponentData component : fixture.draft().components.values()) {
                component.descriptor().sortedRequirements()
                        .forEach(requirement -> keys.add(requirement.key()));
            }
            for (String contextId : fixture.draft().contexts.keySet()) {
                for (CapabilityKey<?> key : keys) {
                    assertEquals(
                            RuntimeGraph.resolveDirect(
                                    fixture.draft(), fixture.overlay(), direct, contextId, key),
                            graph.resolve(
                                    fixture.draft(), fixture.overlay(), indexed, contextId, key),
                            "round " + round + " context " + contextId + " key " + key);
                }
            }
            for (RuntimeView.ComponentData component : fixture.draft().components.values()) {
                assertEquals(
                        RuntimeGraph.effectiveBindingsDirect(
                                fixture.draft(), fixture.overlay(), direct, component),
                        graph.effectiveBindings(
                                fixture.draft(), fixture.overlay(), indexed, component),
                        "round " + round + " handle " + component.handleId());
            }
        }
    }

    private record DraftFixture(
            RuntimeView.Draft draft,
            Map<String, RuntimeView.RegistrationData> overlay) {
    }

    private static DraftFixture randomDraftWithOverlay(Random random, int size) {
        RuntimeView.Draft draft = randomDraft(random, size);
        Map<String, RuntimeView.RegistrationData> overlay = new LinkedHashMap<>();
        int overlayCount = random.nextInt(size + 1);
        for (int i = 0; i < overlayCount; i++) {
            int provider = random.nextInt(size);
            String contextId = random.nextBoolean() ? "ctx-root" : "ctx-child";
            // 同名不同类型与同名同类型混合出现，覆盖 shadow 与类型判别两条路径。
            CapabilityKey<?> key = random.nextInt(8) == 0
                    ? CapabilityKey.of("cap-" + provider, Character.class)
                    : CapabilityKey.of("cap-" + provider, String.class);
            overlay.put("tentative-" + i,
                    registration(
                            "tentative-" + i, key, contextId, "act-tentative-" + i));
        }
        return new DraftFixture(draft, overlay);
    }

    @Test
    void graphContainsOnlyIdentityStateAndDoesNotPinIsolatedClassLoader() throws Exception {
        GraphWithLoader holder = graphWithIsolatedLoader();
        RuntimeGraph graph = holder.graph();
        assertGraphFieldsContainNoClassOrClassLoader(graph.getClass(), graph, 0);

        WeakReference<IsolatedClassLoader> loaderReference = holder.loaderReference();
        waitForGc();
        assertNull(loaderReference.get(), "RuntimeGraph retained isolated capability ClassLoader");
    }

    private static GraphWithLoader graphWithIsolatedLoader() throws Exception {
        IsolatedClassLoader loader = new IsolatedClassLoader();
        Class<?> isolatedType = loader.loadClass(IsolatedCapabilityType.class.getName());
        CapabilityKey<?> isolatedKey = CapabilityKey.of("isolated", isolatedType);
        RuntimeView.Draft draft = draft();
        draft.registrations.put("isolated", new RuntimeView.RegistrationData(
                "isolated", isolatedKey, "ctx-root",
                new RuntimeView.OwnerData.Activation("act-isolated"), new Object(), null));
        draft.components.put("consumer", component("consumer", "ctx-root",
                ComponentDescriptor.named("consumer", CapabilityRequirement.required(isolatedKey)),
                "act-consumer", ActivationState.ACTIVE,
                Map.of("isolated", binding("isolated"))));
        draft.activations.put("act-consumer", activation("act-consumer", "consumer", Map.of()));
        draft.capabilityTypes.put("isolated", isolatedType);
        RuntimeGraph graph = draft.graph();
        return new GraphWithLoader(graph, new WeakReference<>(loader));
    }

    private record GraphWithLoader(
            RuntimeGraph graph,
            WeakReference<IsolatedClassLoader> loaderReference) {
    }

    private static RuntimeView.Draft draft() {
        RuntimeView view = RuntimeView.initial();
        RuntimeView.Draft draft = new RuntimeView.Draft(view);
        draft.contexts.put("ctx-child", new RuntimeView.ContextData(
                "ctx-child", "ctx-root", "child", ContextState.ACTIVE, "/root/child"));
        return draft;
    }

    private static RuntimeView.RegistrationData registration(
            String id, CapabilityKey<?> key, String contextId, String activationId) {
        RuntimeView.OwnerData owner = activationId == null
                ? RuntimeView.OwnerData.Host.INSTANCE
                : new RuntimeView.OwnerData.Activation(activationId);
        return new RuntimeView.RegistrationData(id, key, contextId, owner, new Object(), null);
    }

    private static RuntimeView.ComponentData component(
            String id,
            String contextId,
            ComponentDescriptor descriptor,
            String activationId,
            ActivationState state,
            Map<String, RuntimeView.BindingData> bindings) {
        return new RuntimeView.ComponentData(
                id, contextId, id, id, "factory-" + id, ComponentOrigin.host(),
                activationId, null, ComponentState.WAITING, io.knotra.ComponentGoal.RUNNING,
                1, activationId, activationId, descriptor, null);
    }

    private static RuntimeView.ComponentData ownershipChild(
            String id, String parent, String ownerActivation, String currentActivation) {
        return new RuntimeView.ComponentData(
                id, "ctx-root", id, id, "factory-" + id, ComponentOrigin.host(),
                ownerActivation, parent, ComponentState.WAITING,
                io.knotra.ComponentGoal.RUNNING, 1,
                currentActivation, currentActivation, ComponentDescriptor.named(id), null);
    }

    private static RuntimeView.ActivationData activation(
            String id, String handle, Map<String, RuntimeView.BindingData> bindings) {
        return new RuntimeView.ActivationData(
                id, handle, ActivationState.ACTIVE, 1, bindings,
                ComponentDescriptor.named(handle), null);
    }

    private static RuntimeView.BindingData binding(String registrationId) {
        return new RuntimeView.BindingData(
                registrationId, true, CapabilityRequirement.Mode.REQUIRED,
                CapabilityRequirement.CapabilityBinding.PINNED);
    }

    private static RuntimeView.Draft randomDraft(Random random, int size) {
        RuntimeView.Draft draft = draft();
        String[] contexts = {"ctx-root", "ctx-child"};
        for (int i = 0; i < size; i++) {
            String contextId = contexts[random.nextInt(contexts.length)];
            CapabilityKey<String> key = CapabilityKey.of("cap-" + i, String.class);
            draft.registrations.put("reg-" + i,
                    registration("reg-" + i, key, contextId, "act-" + i));
            draft.activations.put("act-" + i,
                    activation("act-" + i, "handle-" + i, new HashMap<>()));
        }
        for (int consumer = 0; consumer < size; consumer++) {
            Map<String, CapabilityRequirement> requirements = new TreeMap<>();
            int dependencyCount = random.nextInt(3);
            for (int edge = 0; edge < dependencyCount; edge++) {
                int provider = random.nextInt(size);
                CapabilityKey<String> key = CapabilityKey.of("cap-" + provider, String.class);
                CapabilityRequirement requirement = random.nextBoolean()
                        ? CapabilityRequirement.required(key)
                        : CapabilityRequirement.dynamicRequired(key);
                requirements.put(key.name(), requirement);
            }
            ActivationState state = random.nextInt(5) == 0
                    ? ActivationState.STOPPING
                    : ActivationState.ACTIVE;
            Map<String, RuntimeView.BindingData> bindings = new HashMap<>();
            requirements.values().stream()
                    .filter(requirement -> requirement.binding()
                            == CapabilityRequirement.CapabilityBinding.PINNED)
                    .forEach(requirement -> {
                        String provider = requirement.key().name().substring("cap-".length());
                        bindings.put(requirement.key().name(), binding("reg-" + provider));
                    });
            draft.components.put("handle-" + consumer, component(
                    "handle-" + consumer,
                    contexts[random.nextInt(contexts.length)],
                    new ComponentDescriptor("component-" + consumer, new LinkedHashSet<>(requirements.values())),
                    "act-" + consumer,
                    state,
                    bindings));
        }
        return draft;
    }

    private static Map<String, Set<String>> referenceDependencyGraph(RuntimeViewReader reader) {
        Map<String, Set<String>> result = new TreeMap<>();
        Map<String, String> activationOwners = new HashMap<>();
        reader.activations().values().forEach(activation ->
                activationOwners.put(activation.activationId(), activation.handleId()));
        for (RuntimeView.ComponentData component : reader.components().values()) {
            if (component.currentActivationId() == null) {
                continue;
            }
            RuntimeView.ActivationData activation =
                    reader.activations().get(component.currentActivationId());
            if (activation == null || !RuntimeView.activationTracksGraph(activation.state())) {
                continue;
            }
            Set<String> providers = new LinkedHashSet<>();
            for (CapabilityRequirement requirement : component.descriptor().sortedRequirements()) {
                RuntimeView.RegistrationData registration =
                        requirement.binding() == CapabilityRequirement.CapabilityBinding.DYNAMIC
                                ? resolveReference(reader, component.contextId(), requirement.key())
                                : registrationByReference(reader, activation, requirement);
                if (registration != null
                        && registration.owner() instanceof RuntimeView.OwnerData.Activation owner) {
                    String provider = activationOwners.get(owner.activationId());
                    if (provider != null) {
                        providers.add(provider);
                    }
                }
            }
            result.put(component.handleId(), providers);
        }
        return result;
    }

    private static RuntimeView.RegistrationData resolveReference(
            RuntimeViewReader reader, String contextId, CapabilityKey<?> key) {
        String current = contextId;
        while (current != null) {
            for (RuntimeView.RegistrationData candidate : reader.registrations().values()) {
                if (candidate.contextId().equals(current) && candidate.key().equals(key)) {
                    return candidate;
                }
            }
            RuntimeView.ContextData context = reader.contexts().get(current);
            current = context == null ? null : context.parentId();
        }
        return null;
    }

    private static RuntimeView.RegistrationData registrationByReference(
            RuntimeViewReader reader,
            RuntimeView.ActivationData activation,
            CapabilityRequirement requirement) {
        RuntimeView.BindingData binding = activation.bindings().get(requirement.key().name());
        if (binding == null || !binding.present()) {
            return null;
        }
        return reader.registrations().get(binding.registrationId());
    }

    private static void assertGraphFieldsContainNoClassOrClassLoader(
            Class<?> type, Object value, int depth) {
        assertTrue(depth < 6, "RuntimeGraph field traversal exceeded expected depth");
        for (; type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                field.setAccessible(true);
                Object child;
                try {
                    child = field.get(value);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
                assertValueContainsNoClassOrClassLoader(child, depth + 1);
            }
        }
    }

    private static void assertValueContainsNoClassOrClassLoader(Object value, int depth) {
        assertTrue(depth < 8, "RuntimeGraph field traversal exceeded expected depth");
        if (value == null) {
            return;
        }
        assertFalse(value instanceof Class<?>, "RuntimeGraph stores Class");
        assertFalse(value instanceof ClassLoader, "RuntimeGraph stores ClassLoader");
        assertFalse(value instanceof CapabilityKey<?>, "RuntimeGraph stores CapabilityKey");
        assertFalse(value instanceof RuntimeView.RegistrationData,
                "RuntimeGraph stores RegistrationData");
        if (value instanceof RuntimeViewReader || value instanceof RuntimeGraph) {
            assertGraphFieldsContainNoClassOrClassLoader(value.getClass(), value, depth);
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> assertValueContainsNoClassOrClassLoader(item, depth + 1));
            map.keySet().forEach(item -> assertValueContainsNoClassOrClassLoader(item, depth + 1));
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> assertValueContainsNoClassOrClassLoader(item, depth + 1));
        } else if (value.getClass().isArray()) {
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) {
                assertValueContainsNoClassOrClassLoader(
                        java.lang.reflect.Array.get(value, i), depth + 1);
            }
        } else if (!isIdentityScalar(value)) {
            assertGraphFieldsContainNoClassOrClassLoader(value.getClass(), value, depth);
        }
    }

    private static boolean isIdentityScalar(Object value) {
        return value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || value.getClass().isPrimitive();
    }

    private static void waitForGc() throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            System.gc();
            Thread.sleep(10);
        }
    }

    private static final class IsolatedClassLoader extends ClassLoader {
        IsolatedClassLoader() {
            super(null);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (IsolatedCapabilityType.class.getName().equals(name)) {
                Class<?> isolated = findClass(name);
                if (resolve) {
                    resolveClass(isolated);
                }
                return isolated;
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!IsolatedCapabilityType.class.getName().equals(name)) {
                throw new ClassNotFoundException(name);
            }
            try {
                String resource = name.replace('.', '/') + ".class";
                try (var input = RuntimeGraphTest.class.getClassLoader()
                        .getResourceAsStream(resource)) {
                    if (input == null) {
                        throw new ClassNotFoundException(name);
                    }
                    var bytes = input.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                }
            } catch (java.io.IOException error) {
                throw new ClassNotFoundException(name, error);
            }
        }
    }
}
