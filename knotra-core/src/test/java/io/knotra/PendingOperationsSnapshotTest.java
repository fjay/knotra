package io.knotra;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class PendingOperationsSnapshotTest {
    private static final PendingOperationsSnapshot.Kind KIND =
            PendingOperationsSnapshot.Kind.COMPONENT_TRANSITION;
    private static final PendingOperationsSnapshot.WaitType WAIT =
            PendingOperationsSnapshot.WaitType.COMPONENT;

    @Test
    void sortsDeterministicallyAndIgnoresAgeInOrdering() {
        PendingOperationsSnapshot.Operation first = operation("b", "second");
        PendingOperationsSnapshot.Operation second = operation("a", "second");
        PendingOperationsSnapshot.Operation third = operation("a", "first");
        PendingOperationsSnapshot snapshot = new PendingOperationsSnapshot(
                true, List.of(first, second, third), 0);

        assertEquals(List.of(third, second, first), snapshot.operations());
        assertEquals(0, snapshot.omitted());
    }

    @Test
    void limitsTo128OperationsAndReportsOmitted() {
        List<PendingOperationsSnapshot.Operation> operations = new ArrayList<>();
        for (int index = 0; index < 131; index++) {
            operations.add(operation("target-" + index, "detail"));
        }
        PendingOperationsSnapshot snapshot =
                new PendingOperationsSnapshot(false, operations, 999);

        assertEquals(128, snapshot.operations().size());
        assertEquals(3, snapshot.omitted());
        assertEquals("target-0", snapshot.operations().getFirst().targetId());
        assertEquals("target-96", snapshot.operations().getLast().targetId());
    }

    @Test
    void truncatesByUnicodeCodePointAndNeverSplitsSurrogates() {
        String emoji = "\uD83D\uDE00";
        String target = emoji.repeat(129);
        String detail = "x\uD83D\uDE00y".repeat(200);
        PendingOperationsSnapshot.Operation operation =
                new PendingOperationsSnapshot.Operation(
                        KIND, target, WAIT, Duration.ZERO, detail);

        assertEquals(128, operation.targetId().codePointCount(
                0, operation.targetId().length()));
        assertTrue(operation.targetId().endsWith(emoji));
        assertEquals(512, operation.detail().codePointCount(
                0, operation.detail().length()));
        assertFalse(Character.isHighSurrogate(
                operation.detail().charAt(operation.detail().length() - 1)));
    }

    @Test
    void rejectsNullAndNegativeAgeAndRemainsImmutable() {
        assertThrows(NullPointerException.class, () -> new PendingOperationsSnapshot.Operation(
                null, "target", WAIT, Duration.ZERO, "detail"));
        assertThrows(NullPointerException.class, () -> new PendingOperationsSnapshot.Operation(
                KIND, null, WAIT, Duration.ZERO, "detail"));
        assertThrows(IllegalArgumentException.class, () -> new PendingOperationsSnapshot.Operation(
                KIND, "target", WAIT, Duration.ofNanos(-1), "detail"));

        PendingOperationsSnapshot snapshot = new PendingOperationsSnapshot(
                false, List.of(operation("target", "detail")), 0);
        assertThrows(UnsupportedOperationException.class, () ->
                snapshot.operations().clear());
    }

    @Test
    void renderIsDeterministicBoundedAndEscapesLineBreaks() {
        PendingOperationsSnapshot.Operation operation =
                new PendingOperationsSnapshot.Operation(
                        PendingOperationsSnapshot.Kind.RUNTIME_CLOSE,
                        "runtime\none",
                        PendingOperationsSnapshot.WaitType.RUNTIME_DRAIN,
                        Duration.ofNanos(7),
                        "detail\rtwo");
        List<PendingOperationsSnapshot.Operation> renderedOperations =
                new ArrayList<>();
        for (int index = 0; index < 130; index++) {
            renderedOperations.add(runtimeOperation("target-" + index));
        }
        renderedOperations.add(operation);
        PendingOperationsSnapshot snapshot = new PendingOperationsSnapshot(
                true, renderedOperations, 0);

        String rendered = snapshot.render();
        assertEquals(rendered, snapshot.render());
        assertTrue(rendered.contains("closeRequested=true"));
        assertTrue(rendered.contains("RUNTIME_CLOSE|runtime\\none|RUNTIME_DRAIN|PT0.000000007S|detail\\rtwo"));
        assertTrue(rendered.endsWith("omitted=3"));
        assertTrue(rendered.lines().count() <= 130);
    }

    @Test
    void exposesCrossModuleDiagnosticClassificationsWithStableRender() {
        PendingOperationsSnapshot snapshot = new PendingOperationsSnapshot(
                false,
                List.of(
                        new PendingOperationsSnapshot.Operation(
                                PendingOperationsSnapshot.Kind.LOADER_OPERATION,
                                "loader",
                                PendingOperationsSnapshot.WaitType.COORDINATOR,
                                Duration.ZERO,
                                "detail"),
                        new PendingOperationsSnapshot.Operation(
                                PendingOperationsSnapshot.Kind.LOADER_OPERATION,
                                "execution",
                                PendingOperationsSnapshot.WaitType.USER_CALLBACK,
                                Duration.ZERO,
                                "a"),
                        new PendingOperationsSnapshot.Operation(
                                PendingOperationsSnapshot.Kind.LOADER_OPERATION,
                                "execution",
                                PendingOperationsSnapshot.WaitType.RUNTIME_DRAIN,
                                Duration.ZERO,
                                "z"),
                        new PendingOperationsSnapshot.Operation(
                                PendingOperationsSnapshot.Kind.RUNTIME_CLOSE,
                                "executor",
                                PendingOperationsSnapshot.WaitType.EXECUTOR_TERMINATION,
                                Duration.ZERO,
                                "detail"),
                        new PendingOperationsSnapshot.Operation(
                                PendingOperationsSnapshot.Kind.EVENT_SUBSCRIPTION_DRAIN,
                                "subscription",
                                PendingOperationsSnapshot.WaitType.LISTENER,
                                Duration.ZERO,
                                "detail"),
                        new PendingOperationsSnapshot.Operation(
                                PendingOperationsSnapshot.Kind.ARTIFACT_MOUNT,
                                "mount",
                                PendingOperationsSnapshot.WaitType.MOUNTS_IN_FLIGHT,
                                Duration.ZERO,
                                "detail"),
                        new PendingOperationsSnapshot.Operation(
                                PendingOperationsSnapshot.Kind.EVENT_DISPATCH,
                                "dispatch",
                                PendingOperationsSnapshot.WaitType.DISPATCH,
                                Duration.ZERO,
                                "detail"),
                        new PendingOperationsSnapshot.Operation(
                                PendingOperationsSnapshot.Kind.ARTIFACT_DRAIN,
                                "drain",
                                PendingOperationsSnapshot.WaitType.PF4J_STOP_UNLOAD,
                                Duration.ZERO,
                                "detail")
                ),
                0);

        assertEquals(
                "closeRequested=false\n"
                        + "ARTIFACT_DRAIN|drain|PF4J_STOP_UNLOAD|PT0S|detail\n"
                        + "ARTIFACT_MOUNT|mount|MOUNTS_IN_FLIGHT|PT0S|detail\n"
                        + "EVENT_DISPATCH|dispatch|DISPATCH|PT0S|detail\n"
                        + "EVENT_SUBSCRIPTION_DRAIN|subscription|LISTENER|PT0S|detail\n"
                        + "LOADER_OPERATION|execution|RUNTIME_DRAIN|PT0S|z\n"
                        + "LOADER_OPERATION|execution|USER_CALLBACK|PT0S|a\n"
                        + "LOADER_OPERATION|loader|COORDINATOR|PT0S|detail\n"
                        + "RUNTIME_CLOSE|executor|EXECUTOR_TERMINATION|PT0S|detail\n"
                        + "omitted=0",
                snapshot.render());
    }

    private static PendingOperationsSnapshot.Operation runtimeOperation(String target) {
        return new PendingOperationsSnapshot.Operation(
                PendingOperationsSnapshot.Kind.RUNTIME_CLOSE,
                target,
                PendingOperationsSnapshot.WaitType.RUNTIME_DRAIN,
                Duration.ZERO,
                "detail");
    }

    private static PendingOperationsSnapshot.Operation operation(String target, String detail) {
        return new PendingOperationsSnapshot.Operation(
                KIND, target, WAIT, Duration.ZERO, detail);
    }
}
