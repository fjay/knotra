package io.knotra.internal;

import io.knotra.ActivationContext;
import io.knotra.Component;
import io.knotra.ComponentDescriptor;
import io.knotra.ComponentFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loaded by ForeignRestartHandoffMemoryTest's isolated class loader. */
public class IsolatedRestartComponentFactory implements ComponentFactory<String> {
    private final AtomicBoolean firstStart;
    private final CountDownLatch startEntered;
    private final CountDownLatch releaseStart;

    public IsolatedRestartComponentFactory(
            AtomicBoolean firstStart,
            CountDownLatch startEntered,
            CountDownLatch releaseStart) {
        this.firstStart = firstStart;
        this.startEntered = startEntered;
        this.releaseStart = releaseStart;
    }

    @Override
    public Component<String> create() {
        return new IsolatedRestartComponent(firstStart, startEntered, releaseStart);
    }
}

/** Loaded alongside IsolatedRestartComponentFactory. */
class IsolatedRestartComponent implements Component<String> {
    private final AtomicBoolean firstStart;
    private final CountDownLatch startEntered;
    private final CountDownLatch releaseStart;

    IsolatedRestartComponent(
            AtomicBoolean firstStart,
            CountDownLatch startEntered,
            CountDownLatch releaseStart) {
        this.firstStart = firstStart;
        this.startEntered = startEntered;
        this.releaseStart = releaseStart;
    }

    @Override
    public ComponentDescriptor descriptor() {
        return ComponentDescriptor.named("isolated-restart-component");
    }

    @Override
    public void start(ActivationContext context, String config) throws Exception {
        if (firstStart.compareAndSet(false, true)) {
            startEntered.countDown();
            assertTrue(releaseStart.await(10, TimeUnit.SECONDS));
        }
    }
}
