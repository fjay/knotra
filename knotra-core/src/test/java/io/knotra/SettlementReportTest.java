package io.knotra;

final class SettlementReportTest {

    @org.junit.jupiter.api.Test
    void emptyWaitingAndDisposedAffectedSetsHaveExplicitSemantics() {
        SettlementReport empty = new SettlementReport(0, java.util.List.of(), java.util.List.of());
        org.junit.jupiter.api.Assertions.assertFalse(empty.hasAffectedMounts());
        org.junit.jupiter.api.Assertions.assertFalse(empty.hasFailedMounts());
        org.junit.jupiter.api.Assertions.assertTrue(empty.affectedMounts().isEmpty());
        RuntimeDiagnostic diagnostic = new RuntimeDiagnostic(
                DiagnosticCode.MISSING_CAPABILITY, "mount", "waiting");
        SettlementReport waiting = new SettlementReport(
                1,
                java.util.List.of(new SettlementReport.MountOutcome(
                        "mount", "mount", ComponentState.WAITING, java.util.List.of(diagnostic))),
                java.util.List.of());
        org.junit.jupiter.api.Assertions.assertTrue(waiting.hasAffectedMounts());
        org.junit.jupiter.api.Assertions.assertFalse(waiting.hasFailedMounts());
        SettlementReport disposed = new SettlementReport(
                2,
                java.util.List.of(new SettlementReport.MountOutcome(
                        "mount", "mount", ComponentState.DISPOSED, java.util.List.of())),
                java.util.List.of());
        org.junit.jupiter.api.Assertions.assertTrue(disposed.hasAffectedMounts());
        org.junit.jupiter.api.Assertions.assertFalse(disposed.hasFailedMounts());
    }

    @org.junit.jupiter.api.Test
    void reportDiagnosticsAreScopedToAffectedMounts() throws Exception {
        KnotraRuntime runtime = KnotraRuntime.create();
        try {
            CapabilityKey<String> affected = CapabilityKey.of("settlement-affected", String.class);
            CapabilityKey<String> unrelated = CapabilityKey.of("settlement-unrelated", String.class);

            MountHandle failed = TestKit.mount(runtime, runtime.root(), "unrelated-failed",
                    (context, config) -> { throw new IllegalStateException("old failure"); });
            org.junit.jupiter.api.Assertions.assertEquals(
                    ComponentState.FAILED, TestKit.settle(failed).call());

            MountHandle waiting = TestKit.mount(runtime, runtime.root(), "unrelated-waiting",
                    (context, config) -> { },
                    CapabilityRequirement.required(unrelated));
            org.junit.jupiter.api.Assertions.assertEquals(
                    ComponentState.WAITING, TestKit.settle(waiting).call());

            MountHandle consumer = TestKit.mount(runtime, runtime.root(), "affected-consumer",
                    (context, config) -> {
                        if ("two".equals(context.require(affected))) {
                            throw new IllegalStateException("new failure");
                        }
                    },
                    CapabilityRequirement.required(affected));

            PublicationChange<String> pubChange = runtime.publish(affected, "one");
            Publication<String> publication = pubChange.publication();
            pubChange.awaitSettled(java.time.Duration.ofSeconds(10));
            org.junit.jupiter.api.Assertions.assertEquals(
                    ComponentState.ACTIVE, TestKit.settle(consumer).call());

            SettlementReport report = publication.update("two")
                    .awaitSettled(java.time.Duration.ofSeconds(10));

            org.junit.jupiter.api.Assertions.assertTrue(report.hasAffectedMounts());
            org.junit.jupiter.api.Assertions.assertTrue(report.hasFailedMounts());
            org.junit.jupiter.api.Assertions.assertFalse(report.affectedMounts().isEmpty());
            org.junit.jupiter.api.Assertions.assertEquals(
                    java.util.List.of(consumer.handleId()),
                    report.mountOutcomes().stream().map(SettlementReport.MountOutcome::handleId).toList());
            org.junit.jupiter.api.Assertions.assertTrue(report.diagnostics().stream()
                    .allMatch(diagnostic -> consumer.handleId().equals(diagnostic.targetId())));
            org.junit.jupiter.api.Assertions.assertTrue(report.mountOutcomes().stream()
                    .allMatch(outcome -> outcome.diagnostics().stream()
                            .allMatch(diagnostic -> consumer.handleId().equals(diagnostic.targetId()))));
        } finally {
            runtime.close();
        }
    }


    @org.junit.jupiter.api.Test
    void mountDisposalHistoryIsNotAccumulatedByTheRuntimeKernel() throws Exception {
        KnotraRuntime runtime = KnotraRuntime.create();
        try {
            for (int index = 0; index < 100; index++) {
                String mountId = "temporary-" + index;
                ComponentFactory<NoConfig> factory = TestKit.factory(
                        mountId,
                        new TestKit.Scripted<>(
                                ComponentDescriptor.named(mountId),
                                (context, config) -> { }));
                TransactionReceipt<MountHandle> receipt =
                        runtime.advanced().transact(transaction -> {
                            MountHandle handle =
                                    transaction.mount(runtime.root(), mountId, factory);
                            transaction.dispose(handle);
                            return handle;
                        });
                SettlementReport report = receipt.settlement()
                        .awaitSettled(java.time.Duration.ofSeconds(10));
                SettlementReport.MountOutcome outcome =
                        report.outcome(receipt.value().handleId()).orElseThrow();
                org.junit.jupiter.api.Assertions.assertEquals(
                        ComponentState.DISPOSED, outcome.state());
                org.junit.jupiter.api.Assertions.assertEquals(
                        mountId, outcome.mountId());
                org.junit.jupiter.api.Assertions.assertEquals(
                        ComponentState.DISPOSED, receipt.value().state());
            }

            for (java.lang.reflect.Field field : runtime.getClass().getDeclaredFields()) {
                org.junit.jupiter.api.Assertions.assertFalse(
                        field.getName().equals("terminalComponents")
                                || field.getName().equals("terminalMountIds"),
                        field.getName());
            }
            org.junit.jupiter.api.Assertions.assertTrue(
                    runtime.advanced().snapshot().mounts().isEmpty());
        } finally {
            runtime.close();
        }
    }
}
