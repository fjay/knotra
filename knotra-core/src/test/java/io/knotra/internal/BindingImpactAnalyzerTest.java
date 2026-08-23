package io.knotra.internal;

import io.knotra.ActivationState;
import io.knotra.CapabilityKey;
import io.knotra.CapabilityRequirement;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentOrigin;
import io.knotra.ComponentState;
import io.knotra.ContextState;
import io.knotra.ComponentGoal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class BindingImpactAnalyzerTest {
    private static final CapabilityKey<String> CAP =
            CapabilityKey.of("phase-capability", String.class);

    @Test
    void phaseCachesEffectiveBindingsUntilTheNextDraftMutation() {
        RuntimeView.Draft draft = draft();
        draft.registrations.put("old-registration",
                registration("old-registration"));
        RuntimeView.ComponentData component = component(draft);
        draft.components.put("consumer", component);

        BindingImpactAnalyzer.Phase phase = BindingImpactAnalyzer.Phase.of(draft);
        Map<String, RuntimeView.BindingData> first = phase.effectiveBindings(draft, component);
        assertSame(first, phase.effectiveBindings(draft, component));
        assertEquals("old-registration", first.get("phase-capability").registrationId());

        draft.registrations.remove("old-registration");
        draft.registrations.put("new-registration",
                registration("new-registration"));

        BindingImpactAnalyzer.Phase rebuilt = BindingImpactAnalyzer.Phase.of(draft);
        assertEquals("new-registration",
                rebuilt.effectiveBindings(draft, component)
                        .get("phase-capability")
                        .registrationId());
    }

    private static RuntimeView.Draft draft() {
        RuntimeView.Draft draft = new RuntimeView.Draft(RuntimeView.initial());
        draft.contexts.put("ctx-child", new RuntimeView.ContextData(
                "ctx-child", "ctx-root", "child", ContextState.ACTIVE, "/root/child"));
        return draft;
    }

    private static RuntimeView.RegistrationData registration(String id) {
        return new RuntimeView.RegistrationData(
                id,
                CAP,
                "ctx-root",
                new RuntimeView.OwnerData.Host(),
                id,
                null,
                null);
    }

    private static RuntimeView.ComponentData component(RuntimeView.Draft draft) {
        ComponentDescriptor descriptor = ComponentDescriptor.named(
                "consumer", CapabilityRequirement.required(CAP));
        RuntimeView.ActivationData activation = new RuntimeView.ActivationData(
                "act-consumer",
                "consumer",
                ActivationState.ACTIVE,
                1,
                Map.of(),
                descriptor,
                null);
        draft.activations.put(activation.activationId(), activation);
        return new RuntimeView.ComponentData(
                "consumer",
                "ctx-root",
                "consumer",
                "consumer",
                "consumer-factory",
                ComponentOrigin.host(),
                activation.activationId(),
                null,
                ComponentState.ACTIVE,
                ComponentGoal.RUNNING,
                1,
                activation.activationId(),
                activation.activationId(),
                descriptor,
                null);
    }
}
