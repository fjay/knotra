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

/** 权威 Simple API 示例；README 中的代码片段均以此代码为真源。 */
public final class QuickStartExample {

    public record Result(
            String firstValue,
            String secondValue,
            Publication<Greeting> publication,
            boolean firstReportAffectedMounts,
            boolean secondReportAffectedMounts,
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

        // try-with-resources 用于保持示例精简；其 close() 会无界阻塞等待停机。
        // 生产环境应调用 closeAsync() 并使用有界 get(timeout) 等待，详见 docs/Knotra 线程模型与生产实践.md。
        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            PublicationChange<Greeting> firstChange =
                    runtime.publish(Greeting.class, new ConstantGreeting("v1"));
            Publication<Greeting> publication = firstChange.publication();

            SettlementReport firstReport = firstChange.awaitSettled(timeout);
            check(!firstReport.hasFailedMounts(), "first publication must not fail a mount");
            check(!firstReport.hasAffectedMounts(), "no mounts exist yet when the first value publishes");

            MountHandle renderer = Beans
                    .component("greeting-renderer")
                    .with(Beans.dynamic(Greeting.class))
                    .create((Greeting greeting) -> new GreetingRenderer(greeting, rendererInstances))
                    .provideAs(RenderedGreeting.class)
                    .mount(runtime);
            renderer.requireActive(timeout);

            String firstValue = runtime.require(RenderedGreeting.class)
                    .render("Knotra");

            PublicationChange<Greeting> secondChange =
                    publication.update(new ConstantGreeting("v2"));
            SettlementReport secondReport = secondChange.awaitSettled(timeout);

            check(!secondReport.hasFailedMounts(), "renderer settlement must not fail");
            check(!secondReport.hasAffectedMounts(), "a dynamic proxy consumer is not activation-affected by replacement");
            check(secondChange.publication() == publication,
                    "Publication must stay stable across updates");
            check(secondChange.generation() > firstChange.generation(),
                    "each update must advance the runtime generation");
            renderer.requireActive(timeout);

            String secondValue = runtime.require(RenderedGreeting.class)
                    .render("Knotra");

            return new Result(
                    firstValue,
                    secondValue,
                    publication,
                    firstReport.hasAffectedMounts(),
                    secondReport.hasAffectedMounts(),
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
