package io.knotra.internal;

import io.knotra.ComponentDescriptor;
import io.knotra.ComponentState;
import io.knotra.DiagnosticCode;
import io.knotra.FailurePhase;
import io.knotra.KnotraRuntime;
import io.knotra.MountFactory;
import io.knotra.MountHandle;
import io.knotra.RuntimeDiagnostic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户 start() 失败证据的锁外契约：用户可覆写 getMessage()/getCause() 并在其中
 * 阻塞或重入，协调器在这类格式化期间必须保持可提交，诊断文本仍保持稳定。
 */
final class HostileStartErrorEvidenceTest {
    private final KnotraRuntime publicRuntime = KnotraRuntime.create();

    @AfterEach
    void tearDown() {
        publicRuntime.close();
    }

    @Test
    void hostileThrowableFormattingLeavesCoordinatorCommittable() throws Exception {
        CountDownLatch formattingEntered = new CountDownLatch(1);
        CountDownLatch releaseFormatting = new CountDownLatch(1);
        HostileStartError hostile = new HostileStartError(formattingEntered, releaseFormatting);

        MountHandle failed = publicRuntime.advanced().transact(transaction ->
                transaction.mount(
                        publicRuntime.root(),
                        "hostile",
                        MountFactory.of(
                                "hostile",
                                ComponentDescriptor.named("hostile"),
                                context -> {
                                    throw hostile;
                                }))).value();

        assertTrue(
                formattingEntered.await(10, TimeUnit.SECONDS),
                "start failure evidence capture never read the hostile throwable");

        // 格式化线程仍阻塞在用户覆写 getMessage() 内：另一线程必须还能提交结构
        // 事务并完成无关 Activation，证明协调器未被失败格式化占用。
        CompletableFuture<ComponentState> witnessSettled = CompletableFuture.supplyAsync(
                () -> publicRuntime.advanced().transact(transaction -> transaction.mount(
                        publicRuntime.root(),
                        "witness",
                        MountFactory.of(
                                "witness",
                                ComponentDescriptor.named("witness"),
                                context -> {
                                }))).value())
                .thenCompose(handle -> handle.whenSettled().toCompletableFuture());
        assertEquals(
                ComponentState.ACTIVE,
                witnessSettled.get(15, TimeUnit.SECONDS),
                "coordinator stayed occupied by hostile throwable formatting");

        releaseFormatting.countDown();
        assertEquals(
                ComponentState.FAILED,
                failed.awaitSettled(Duration.ofSeconds(10)));

        RuntimeDiagnostic diagnostic = publicRuntime.advanced().snapshot().diagnostics()
                .stream()
                .filter(item -> item.code() == DiagnosticCode.ACTIVATION_FAILED
                        && item.targetId().equals(failed.handleId()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                HostileStartError.class.getName() + ": hostile-start-detail",
                diagnostic.message());
        assertEquals(FailurePhase.ACTIVATION, diagnostic.failure().phase());
        assertEquals("hostile-start-detail", diagnostic.failure().message());
        assertTrue(
                hostile.messageReads.get() >= 2,
                "evidence capture must format summary and detail from the throwable");
        assertTrue(
                hostile.causeReads.get() >= 1,
                "evidence capture must inspect the throwable cause chain");
    }

    private static final class HostileStartError extends Exception {
        private final CountDownLatch formattingEntered;
        private final CountDownLatch releaseFormatting;
        final AtomicInteger messageReads = new AtomicInteger();
        final AtomicInteger causeReads = new AtomicInteger();

        HostileStartError(
                CountDownLatch formattingEntered,
                CountDownLatch releaseFormatting) {
            this.formattingEntered = formattingEntered;
            this.releaseFormatting = releaseFormatting;
        }

        @Override
        public String getMessage() {
            messageReads.incrementAndGet();
            formattingEntered.countDown();
            try {
                releaseFormatting.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            return "hostile-start-detail";
        }

        @Override
        public Throwable getCause() {
            causeReads.incrementAndGet();
            return null;
        }
    }
}
