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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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

    private KnotraRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = KnotraRuntime.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    @Test
    void stablePublicationAndDynamicBeanFollowTheSimpleApiLifecycle() throws Exception {
        PublicationChange<Gateway> first = runtime.publish(Gateway.class, new ConstantGateway("one"));
        Publication<Gateway> publication = first.publication();

        assertEquals(Gateway.class.getName(), publication.key().name());
        assertEquals(PublicationOperation.PUBLISH, first.operation());
        assertEquals(PublicationState.PUBLISHED, publication.state());
        SettlementReport firstReport = first.awaitSettled(Duration.ofSeconds(10));
        assertTrue(firstReport.generation() >= 0);
        assertFalse(firstReport.allActive(),
                "an empty affected set is explicitly not an all-active health claim");

        BeanDefinition<DynamicGatewayRenderer> dynamicRenderer = Beans
                .component("dynamic-gateway-renderer")
                .with(Beans.dynamic(Gateway.class))
                .create(DynamicGatewayRenderer::new)
                .provideAs(RenderedGreeting.class, renderer -> renderer)
                .build();
        MountHandle rendererHandle = dynamicRenderer.mount(runtime);
        rendererHandle.requireActive(Duration.ofSeconds(10));

        BeanDefinition<FixedGatewayConsumer> fixedConsumer = Beans
                .component("fixed-gateway-consumer")
                .with(Beans.required(Gateway.class))
                .create(FixedGatewayConsumer::new)
                .build();
        MountHandle fixedHandle = fixedConsumer.mount(runtime);
        fixedHandle.requireActive(Duration.ofSeconds(10));

        assertEquals("gateway: one", runtime.root().view().require(RenderedGreeting.class).render());

        PublicationChange<Gateway> second = publication.update(new ConstantGateway("two"));
        SettlementReport secondReport = second.awaitSettled(Duration.ofSeconds(10));
        assertTrue(second.whenSettled().toCompletableFuture().isDone());
        assertEquals(PublicationOperation.UPDATE, second.operation());
        assertNotEquals(first.registration().registrationId(), second.registration().registrationId());
        assertTrue(secondReport.hasFailedMounts(), () -> secondReport.toString());
        assertFalse(secondReport.allActive());
        assertEquals(ComponentState.FAILED, fixedHandle.state());
        rendererHandle.requireActive(Duration.ofSeconds(10));
        assertEquals("gateway: two", runtime.root().view().require(RenderedGreeting.class).render());

        PublicationChange<Gateway> third = publication.update(new ConstantGateway("three"));
        SettlementReport thirdReport = third.awaitSettled(Duration.ofSeconds(10));
        assertEquals(PublicationOperation.UPDATE, third.operation());
        assertNotEquals(second.registration().registrationId(), third.registration().registrationId());
        assertTrue(secondReport.generation() < thirdReport.generation());
        rendererHandle.requireActive(Duration.ofSeconds(10));
        assertEquals("gateway: three", runtime.root().view().require(RenderedGreeting.class).render());

        PublicationChange<Gateway> removed = publication.unpublish();
        SettlementReport removedReport = removed.awaitSettled(Duration.ofSeconds(10));
        assertEquals(PublicationOperation.UNPUBLISH, removed.operation());
        assertNull(removed.registration());
        assertTrue(thirdReport.generation() < removedReport.generation());
        assertEquals(PublicationState.UNPUBLISHED, publication.state());
        assertTrue(runtime.root().view().find(Gateway.class).isEmpty());
        assertSame(removed, publication.unpublish());
    }
}
