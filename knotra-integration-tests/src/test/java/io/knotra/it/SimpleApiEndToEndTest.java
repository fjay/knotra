package io.knotra.it;

import java.time.Duration;

import io.knotra.ComponentState;
import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.Publication;
import io.knotra.PublicationChange;
import io.knotra.PublicationOperation;
import io.knotra.PublicationState;
import io.knotra.SettlementReport;
import io.knotra.beans.Beans;
import io.knotra.beans.BeanDefinition;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.parallel.ResourceLock(IntegrationTestKit.INTEGRATION_COORDINATOR_LOCK)
final class SimpleApiEndToEndTest {

    interface Gateway {
        String message();
    }

    interface RenderedGreeting {
        String render();
    }

    record ConstantGateway(String value) implements Gateway {
        @Override
        public String message() {
            return value;
        }
    }

    record DynamicGatewayRenderer(Gateway gateway) implements RenderedGreeting {
        @Override
        public String render() {
            return "gateway: " + gateway.message();
        }
    }

    record FixedGatewayConsumer(Gateway gateway) {
        FixedGatewayConsumer {
            if ("two".equals(gateway.message())) {
                throw new IllegalStateException("fixed generation cannot consume replacement");
            }
        }
    }

    @RegisterExtension
    private final KnotraIntegrationExtension runtimeExtension =
            KnotraIntegrationExtension.defaults();

    @Test
    void stablePublicationAndDynamicBeanFollowTheSimpleApiLifecycle(
            KnotraRuntime runtime) throws Exception {
        PublicationChange<Gateway> first = runtime.publish(Gateway.class, new ConstantGateway("one"));
        Publication<Gateway> publication = first.publication();

        assertEquals(Gateway.class.getName(), publication.key().name());
        assertEquals(PublicationOperation.PUBLISH, first.operation());
        assertEquals(PublicationState.PUBLISHED, publication.state());
        SettlementReport firstReport = first.awaitSettled(Duration.ofSeconds(10));
        assertTrue(firstReport.generation() >= 0);
        assertFalse(firstReport.hasAffectedMounts());

        var dynamicGateway = Beans.dynamic(Gateway.class);
        MountHandle rendererHandle = Beans
                .component("dynamic-gateway-renderer")
                .with(dynamicGateway)
                .create(deps -> new DynamicGatewayRenderer(deps.get(dynamicGateway)))
                .provideAs(RenderedGreeting.class)
                .mount(runtime);
        rendererHandle.requireActive(Duration.ofSeconds(10));

        var fixedGateway = Beans.fixed(Gateway.class);
        MountHandle fixedHandle = Beans
                .component("fixed-gateway-consumer")
                .with(fixedGateway)
                .create(deps -> new FixedGatewayConsumer(deps.get(fixedGateway)))
                .mount(runtime);
        fixedHandle.requireActive(Duration.ofSeconds(10));

        assertEquals("gateway: one", runtime.require(RenderedGreeting.class).render());

        PublicationChange<Gateway> second = publication.update(new ConstantGateway("two"));
        SettlementReport secondReport = second.awaitSettled(Duration.ofSeconds(10));
        assertTrue(second.whenSettled().toCompletableFuture().isDone());
        assertEquals(PublicationOperation.UPDATE, second.operation());
        assertTrue(first.generation() < second.generation());
        assertTrue(secondReport.hasAffectedMounts());
        assertTrue(secondReport.hasFailedMounts(), () -> secondReport.toString());
        assertEquals(ComponentState.FAILED, fixedHandle.state());
        rendererHandle.requireActive(Duration.ofSeconds(10));
        assertEquals("gateway: two", runtime.require(RenderedGreeting.class).render());

        PublicationChange<Gateway> third = publication.update(new ConstantGateway("three"));
        SettlementReport thirdReport = third.awaitSettled(Duration.ofSeconds(10));
        assertEquals(PublicationOperation.UPDATE, third.operation());
        assertTrue(second.generation() < third.generation());
        assertTrue(secondReport.generation() < thirdReport.generation());
        rendererHandle.requireActive(Duration.ofSeconds(10));
        assertEquals("gateway: three", runtime.require(RenderedGreeting.class).render());

        PublicationChange<Gateway> removed = publication.unpublish();
        SettlementReport removedReport = removed.awaitSettled(Duration.ofSeconds(10));
        assertEquals(PublicationOperation.UNPUBLISH, removed.operation());
        assertTrue(thirdReport.generation() < removedReport.generation());
        assertEquals(PublicationState.UNPUBLISHED, publication.state());
        assertTrue(runtime.find(Gateway.class).isEmpty());
        assertSame(removed, publication.unpublish());
    }

}
