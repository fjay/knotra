package io.knotra.internal;

import io.knotra.ComponentDescriptor;
import io.knotra.FailureInfo;
import io.knotra.FailurePhase;
import io.knotra.MountFactory;
import io.knotra.MountOptions;
import io.knotra.NoConfig;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ComponentRuntime 状态封装的领域语义与并发可见性契约。
 *
 * <p>重点覆盖：三个不可变 tuple 的原子发布、ActivationSlots 合法组合不变量、
 * failure/reconcile 领域方法的行为等价性、coordinator 锁 assert 契约，
 * 以及 transition 链方法只依赖 chainLock、不要求协调器锁。</p>
 */
final class ComponentRuntimeStateTest {
    private static final Object COORDINATOR = new Object();

    @Test
    void lockedMutatorsRequireCoordinatorLock() {
        ComponentRuntime component = component();
        ActivationRuntime activation = activation(component, "a1");

        assertThrows(AssertionError.class, () -> component.updateDesiredLocked("cfg", 2));
        assertThrows(AssertionError.class, () -> component.claimCurrentLocked(activation));
        assertThrows(AssertionError.class, component::clearCurrentLocked);
        assertThrows(AssertionError.class, () -> component.markFailedCleanupLocked(activation));
        assertThrows(AssertionError.class, component::clearFailedCleanupLocked);
        assertThrows(AssertionError.class, () -> component.retainFailedCleanupLocked(activation));
        assertThrows(AssertionError.class, () ->
                component.recordStartFailureLocked(true, "boom", FailureInfo.EMPTY));
        assertThrows(AssertionError.class, component::clearStartFailureLocked);
        assertThrows(AssertionError.class, () ->
                component.recordCleanupFailureLocked("boom", FailureInfo.EMPTY));
        assertThrows(AssertionError.class, component::clearCleanupFailureLocked);
        assertThrows(AssertionError.class, () ->
                component.recordReconcileFingerprintLocked("fp"));
        assertThrows(AssertionError.class, component::resetAutoRestartLocked);
        assertThrows(AssertionError.class, () -> component.suppressAutoRestartLocked(true));
        assertThrows(AssertionError.class, component::clearBlockedNonConvergentLocked);
        assertThrows(AssertionError.class, () -> component.planReconcileLocked("fp", 3));
        assertThrows(AssertionError.class, () ->
                component.requestRetryLocked(ComponentRuntime.RetryIntent.CLEANUP));
        assertThrows(AssertionError.class, component::consumeActivationRetryIntentLocked);
        assertThrows(AssertionError.class, component::consumeCleanupRetryIntentLocked);

        synchronized (COORDINATOR) {
            assertDoesNotThrow(() -> component.updateDesiredLocked("cfg", 2));
            assertDoesNotThrow(() -> component.claimCurrentLocked(activation));
            assertDoesNotThrow(component::clearCurrentLocked);
            assertDoesNotThrow(() -> component.markFailedCleanupLocked(activation));
            assertDoesNotThrow(component::clearFailedCleanupLocked);
            assertDoesNotThrow(() -> component.retainFailedCleanupLocked(activation));
            assertDoesNotThrow(() ->
                    component.recordStartFailureLocked(true, "boom", FailureInfo.EMPTY));
            assertDoesNotThrow(component::clearStartFailureLocked);
            assertDoesNotThrow(() ->
                    component.recordCleanupFailureLocked("boom", FailureInfo.EMPTY));
            assertDoesNotThrow(component::clearCleanupFailureLocked);
            assertDoesNotThrow(() -> component.recordReconcileFingerprintLocked("fp"));
            assertDoesNotThrow(component::resetAutoRestartLocked);
            assertDoesNotThrow(() -> component.suppressAutoRestartLocked(true));
            assertDoesNotThrow(component::clearBlockedNonConvergentLocked);
            assertDoesNotThrow(() -> component.planReconcileLocked("fp", 3));
            assertDoesNotThrow(() ->
                    component.requestRetryLocked(ComponentRuntime.RetryIntent.CLEANUP));
            assertDoesNotThrow(component::consumeActivationRetryIntentLocked);
            assertDoesNotThrow(component::consumeCleanupRetryIntentLocked);
        }
    }

    @Test
    void transitionChainDoesNotRequireCoordinatorLock() {
        ComponentRuntime component = component();

        // chainLock 协议独立于协调器；预约/完成不能因为未持协调器锁而失败。
        ComponentRuntime.Reservation reservation =
                component.reserveTransition(System.nanoTime(), "test");
        assertTrue(reservation.created());
        Runnable completion = assertDoesNotThrow(() -> component.finishTransition(
                reservation.future(),
                io.knotra.ComponentState.WAITING));
        completion.run();
        assertTrue(reservation.future().isDone());
        assertNull(component.pendingSnapshot());
    }

    @Test
    void slotTransitionsPreserveLegalCombinations() {
        ComponentRuntime component = component();
        ActivationRuntime first = activation(component, "a1");
        ActivationRuntime second = activation(component, "a2");

        synchronized (COORDINATOR) {
            assertEquals(ComponentRuntime.ActivationSlots.EMPTY, component.slots());
            assertNull(component.current());
            assertNull(component.failedCleanup());

            component.claimCurrentLocked(first);
            assertSame(first, component.current());
            assertNull(component.failedCleanup());
            assertTrue(component.slots().consistent());

            component.markFailedCleanupLocked(first);
            assertSame(first, component.current());
            assertSame(first, component.failedCleanup());
            assertTrue(component.slots().consistent());

            component.clearFailedCleanupLocked();
            assertSame(first, component.current());
            assertNull(component.failedCleanup());

            component.clearCurrentLocked();
            assertNull(component.current());

            component.retainFailedCleanupLocked(second);
            assertSame(second, component.current());
            assertSame(second, component.failedCleanup());
            assertTrue(component.slots().consistent());

            component.clearCurrentLocked();
            component.clearFailedCleanupLocked();
            assertEquals(ComponentRuntime.ActivationSlots.EMPTY, component.slots());
        }
    }

    @Test
    void failureStateHalvesAreIndependentAndAtomicallyPublished() {
        ComponentRuntime component = component();
        FailureInfo startInfo = info("start-failure");
        FailureInfo cleanupInfo = info("cleanup-failure");

        synchronized (COORDINATOR) {
            component.recordStartFailureLocked(true, "start failed", startInfo);
            assertTrue(component.pendingStartFailure());
            assertEquals("start failed", component.lastStartError());
            assertSame(startInfo, component.lastStartFailure());
            assertEquals("", component.lastCleanupError());
            assertEquals(FailureInfo.EMPTY, component.lastCleanupFailure());

            component.recordCleanupFailureLocked("cleanup failed", cleanupInfo);
            assertTrue(component.pendingStartFailure());
            assertEquals("start failed", component.lastStartError());
            assertSame(startInfo, component.lastStartFailure());
            assertEquals("cleanup failed", component.lastCleanupError());
            assertSame(cleanupInfo, component.lastCleanupFailure());

            component.clearStartFailureLocked();
            assertFalse(component.pendingStartFailure());
            assertEquals("", component.lastStartError());
            assertEquals(FailureInfo.EMPTY, component.lastStartFailure());
            assertEquals("cleanup failed", component.lastCleanupError());
            assertSame(cleanupInfo, component.lastCleanupFailure());

            component.clearCleanupFailureLocked();
            assertEquals(FailureInfo.EMPTY, component.lastCleanupFailure());
            assertEquals(initialFailureState(), component.failureState());
        }
    }

    private static ComponentRuntime.ComponentFailureState initialFailureState() {
        return new ComponentRuntime.ComponentFailureState(
                false, "", FailureInfo.EMPTY, "", FailureInfo.EMPTY);
    }

    @Test
    void failureTupleIsAtomicallyPublished() throws Exception {
        ComponentRuntime component = component();
        FailureInfo startInfo = info("start-A");
        FailureInfo cleanupInfo = info("cleanup-A");
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch readerDone = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                ComponentRuntime.ComponentFailureState state = component.failureState();
                boolean startSet = !state.lastStartError().isBlank();
                boolean cleanupSet = !state.lastCleanupError().isBlank();
                if (startSet != (state.lastStartFailure() == startInfo)
                        || cleanupSet != (state.lastCleanupFailure() == cleanupInfo)
                        || state.pendingStartFailure() != startSet) {
                    stop.set(true);
                    throw new AssertionError("torn failure tuple: " + state);
                }
            }
            readerDone.countDown();
        });
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 100_000 && !stop.get(); i++) {
                synchronized (COORDINATOR) {
                    if ((i & 1) == 0) {
                        component.recordStartFailureLocked(
                                true, "start-A", startInfo);
                        component.recordCleanupFailureLocked("cleanup-A", cleanupInfo);
                    } else {
                        component.clearStartFailureLocked();
                        component.clearCleanupFailureLocked();
                    }
                }
            }
        });
        reader.start();
        writer.start();
        writer.join(10_000);
        stop.set(true);
        assertTrue(readerDone.await(1, TimeUnit.SECONDS), "reader must terminate");
        reader.join(1_000);
    }

    @Test
    void reconcileDomainKeepsMaxIterationSemantics() {
        ComponentRuntime component = component();

        synchronized (COORDINATOR) {
            assertTrue(component.planReconcileLocked("fp-a", 3));
            assertEquals(1, component.reconcileState().attempts());
            assertTrue(component.planReconcileLocked("fp-a", 3));
            assertEquals(2, component.reconcileState().attempts());
            assertFalse(component.blockedNonConvergent());
            assertFalse(component.planReconcileLocked("fp-a", 3));
            assertEquals(3, component.reconcileState().attempts());
            assertTrue(component.blockedNonConvergent());

            // 新拓扑指纹重置计数与两个抑制位。
            assertTrue(component.planReconcileLocked("fp-b", 3));
            assertEquals(1, component.reconcileState().attempts());
            assertFalse(component.blockedNonConvergent());

            // 周期抑制优先于重试计数。
            component.suppressAutoRestartLocked(true);
            assertFalse(component.planReconcileLocked("fp-b", 3));
            assertEquals(1, component.reconcileState().attempts());
            assertTrue(component.suppressAutoRestart());

            // resetAutoRestart 只清计数与抑制位，保留指纹。
            component.clearBlockedNonConvergentLocked();
            assertFalse(component.blockedNonConvergent());
            component.resetAutoRestartLocked();
            assertFalse(component.suppressAutoRestart());
            assertEquals(0, component.reconcileState().attempts());
            assertEquals("fp-b", component.reconcileState().fingerprint());

            // 指纹记录只更新指纹，保留其余字段。
            component.suppressAutoRestartLocked(true);
            component.recordReconcileFingerprintLocked("fp-c");
            assertEquals("fp-c", component.reconcileState().fingerprint());
            assertTrue(component.suppressAutoRestart());
            assertEquals(0, component.reconcileState().attempts());
            // 新指纹随后仍会重置抑制并允许重启。
            assertTrue(component.planReconcileLocked("fp-d", 3));
            assertFalse(component.suppressAutoRestart());
        }
    }

    @Test
    void retryIntentIsOneShotPerKind() {
        ComponentRuntime component = component();
        assertEquals(ComponentRuntime.RetryIntent.NONE, component.peekRetryIntent());

        synchronized (COORDINATOR) {
            component.requestRetryLocked(ComponentRuntime.RetryIntent.ACTIVATION);
            assertEquals(ComponentRuntime.RetryIntent.ACTIVATION, component.peekRetryIntent());
            assertFalse(component.consumeCleanupRetryIntentLocked());
            assertEquals(ComponentRuntime.RetryIntent.ACTIVATION, component.peekRetryIntent());
            assertTrue(component.consumeActivationRetryIntentLocked());
            assertEquals(ComponentRuntime.RetryIntent.NONE, component.peekRetryIntent());
            assertFalse(component.consumeActivationRetryIntentLocked());

            component.requestRetryLocked(ComponentRuntime.RetryIntent.CLEANUP);
            assertFalse(component.consumeActivationRetryIntentLocked());
            assertTrue(component.consumeCleanupRetryIntentLocked());
            assertEquals(ComponentRuntime.RetryIntent.NONE, component.peekRetryIntent());
        }
    }

    @Test
    void desiredTuplePublishesConfigAndRevisionTogether() throws Exception {
        ComponentRuntime component = component();
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch readersDone = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                DesiredComponentState state = component.desiredState();
                long revision = state.revision();
                Object config = state.config();
                // 写入端保证偶数代 config="cfg-0"/奇数代 config="cfg-1"；撕裂读取会出现不匹配组合。
                if ((revision % 2 == 0) != "cfg-0".equals(config)) {
                    stop.set(true);
                    throw new AssertionError(
                            "torn desired tuple: " + config + "@" + revision);
                }
            }
            readersDone.countDown();
        });
        Thread writer = new Thread(() -> {
            for (long revision = 1; revision < 200_000 && !stop.get(); revision++) {
                synchronized (COORDINATOR) {
                    component.updateDesiredLocked(
                            revision % 2 == 0 ? "cfg-0" : "cfg-1",
                            revision);
                }
            }
        });
        reader.start();
        writer.start();
        writer.join(10_000);
        stop.set(true);
        assertTrue(readersDone.await(1, TimeUnit.SECONDS), "reader must terminate");
        reader.join(1_000);
    }

    @Test
    void concurrentReadersNeverSeeIllegalSlotCombinations() throws Exception {
        ComponentRuntime component = component();
        ActivationRuntime first = activation(component, "a1");
        ActivationRuntime second = activation(component, "a2");
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch readersDone = new CountDownLatch(2);

        Runnable reader = () -> {
            while (!stop.get()) {
                ComponentRuntime.ActivationSlots slots = component.slots();
                if (!slots.consistent()) {
                    stop.set(true);
                    throw new AssertionError("illegal slot combination observed: " + slots);
                }
            }
            readersDone.countDown();
        };
        Thread readerA = new Thread(reader);
        Thread readerB = new Thread(reader);
        readerA.start();
        readerB.start();

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 100_000 && !stop.get(); i++) {
                synchronized (COORDINATOR) {
                    component.claimCurrentLocked(first);
                    component.markFailedCleanupLocked(first);
                    component.clearFailedCleanupLocked();
                    component.clearCurrentLocked();
                    component.retainFailedCleanupLocked(second);
                    component.clearFailedCleanupLocked();
                    component.clearCurrentLocked();
                }
            }
        });
        writer.start();
        writer.join(10_000);
        stop.set(true);
        assertTrue(readersDone.await(1, TimeUnit.SECONDS), "readers must terminate");
        readerA.join(1_000);
        readerB.join(1_000);
    }

    static ComponentRuntime component() {
        return new ComponentRuntime(
                "handle-state",
                "ctx-state",
                "mount-state",
                prepared(),
                COORDINATOR);
    }

    private static PreparedComponent<?> prepared() {
        return PreparedComponent.prepare(
                MountFactory.of(
                        "state-test-factory",
                        ComponentDescriptor.named("state-test"),
                        context -> { }),
                NoConfig.INSTANCE,
                MountOptions.DEFAULT);
    }

    private static ActivationRuntime activation(ComponentRuntime owner, String id) {
        return new ActivationRuntime(
                id,
                owner,
                NoConfig.INSTANCE,
                1,
                Map.of(),
                List.of(),
                System::nanoTime);
    }

    private static FailureInfo info(String message) {
        return new FailureInfo(
                FailurePhase.ACTIVATION,
                IllegalStateException.class.getName(),
                message,
                List.of(),
                List.of(),
                Instant.EPOCH);
    }
}
