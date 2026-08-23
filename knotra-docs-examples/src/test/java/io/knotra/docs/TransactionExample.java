package io.knotra.docs;

import io.knotra.KnotraRuntime;
import io.knotra.Settlement;
import io.knotra.SettlementReport;
import io.knotra.StagedRegistration;
import io.knotra.TransactionReceipt;

import java.time.Duration;

/** 权威 Advanced API 事务示例；API 指南文档链接至此。 */
public final class TransactionExample {

    public record Result(long generation, boolean published, boolean revoked) {
    }

    public interface Message {
        String value();
    }

    record CommittedMessage(String value) implements Message {
        @Override
        public String value() {
            return value;
        }
    }

    private TransactionExample() {
    }

    public static Result run() {
        Duration timeout = Duration.ofSeconds(10);

        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            TransactionReceipt<StagedRegistration<Message>> receipt =
                    runtime.advanced().transact(transaction -> transaction.provide(
                            runtime.root(),
                            Message.class,
                            new CommittedMessage("committed in one transaction")));

            StagedRegistration<Message> staged = receipt.value();
            require(staged.key().type() == Message.class, "staged registration is typed");
            require(staged.context() == runtime.root(), "staged registration keeps its context");

            SettlementReport report = receipt.awaitSettled(timeout);
            require(runtime.root().view().require(Message.class).value()
                            .equals("committed in one transaction"),
                    "committed value must be visible");

            Settlement revoke = runtime.advanced().transact(tx -> {
                tx.revoke(staged);
                return null;
            });
            revoke.awaitSettled(timeout);
            require(runtime.root().view().find(Message.class).isEmpty(),
                    "a committed staged token can be revoked as an opaque handle");

            return new Result(report.generation(), true, true);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
