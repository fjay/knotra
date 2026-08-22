package io.knotra.docs;

import io.knotra.KnotraRuntime;
import io.knotra.MountHandle;
import io.knotra.Publication;
import io.knotra.PublicationChange;
import io.knotra.SettlementReport;

import io.knotra.beans.BeanDefinition;
import io.knotra.beans.Beans;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Canonical Simple API example; README snippets mirror this source. */
public final class QuickStartExample {

    public record Result(
            String firstValue,
            String secondValue,
            Publication<Greeting> publication,
            boolean firstReportAllActive,
            boolean secondReportAllActive,
            int rendererInstances) {
    }

    public interface Greeting {
        String greet(String name);
    }

    public interface RenderedGreeting {
        String render(String name);
    }

    record ConstantGreeting(String version) implements Greeting {
        @Override
        public String greet(String name) {
            return version + ": Hello, " + name;
        }
    }

    static final class GreetingRenderer implements RenderedGreeting {
        GreetingRenderer(Greeting greeting, AtomicInteger instances) {
            this.greeting = greeting;
            instances.incrementAndGet();
        }

        private final Greeting greeting;

        @Override
        public String render(String name) {
            return greeting.greet(name);
        }
    }

    private QuickStartExample() {
    }

    public static Result run() {
        Duration timeout = Duration.ofSeconds(10);
        AtomicInteger rendererInstances = new AtomicInteger();

        // try-with-resources keeps the canonical sample short; its close() blocks without a
        // timeout. Production shutdown should await closeAsync() with a bounded get(timeout);
        // see docs/Knotra 线程模型与生产实践.md.
        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            PublicationChange<Greeting> firstChange =
                    runtime.publish(Greeting.class, new ConstantGreeting("v1"));
            Publication<Greeting> publication = firstChange.publication();

            SettlementReport firstReport = firstChange.awaitSettled(timeout);
            check(!firstReport.hasFailedMounts(), "first publication must not fail a mount");
            check(!firstReport.allActive(),
                    "an empty affected set is not an all-active health claim");

            BeanDefinition<GreetingRenderer> rendererDefinition = Beans
                    .component("greeting-renderer")
                    .with(Beans.dynamic(Greeting.class))
                    .create((Greeting greeting) -> new GreetingRenderer(greeting, rendererInstances))
                    .provideAs(RenderedGreeting.class, renderer -> renderer)
                    .build();

            MountHandle renderer = rendererDefinition.mount(runtime);
            renderer.requireActive(timeout);

            String firstValue = runtime.root().view()
                    .require(RenderedGreeting.class)
                    .render("Knotra");

            PublicationChange<Greeting> secondChange =
                    publication.update(new ConstantGreeting("v2"));
            SettlementReport secondReport = secondChange.awaitSettled(timeout);

            check(!secondReport.hasFailedMounts(), "renderer settlement must not fail");
            check(!secondReport.allActive(), "a dynamic proxy is not activation-affected by replacement");
            check(secondChange.publication() == publication,
                    "Publication must stay stable across updates");
            check(secondChange.registration().registrationId()
                            != firstChange.registration().registrationId(),
                    "each update must create a new Registration");
            renderer.requireActive(timeout);

            String secondValue = runtime.root().view()
                    .require(RenderedGreeting.class)
                    .render("Knotra");

            return new Result(
                    firstValue,
                    secondValue,
                    publication,
                    firstReport.allActive(),
                    secondReport.allActive(),
                    rendererInstances.get());
        }
    }

    public static void main(String[] args) {
        Result result = run();
        System.out.println(result.firstValue());
        System.out.println("replacing provider");
        System.out.println(result.secondValue());
        System.out.println("renderer instances: " + result.rendererInstances());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
