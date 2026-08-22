package io.knotra.docs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TransactionExampleTest {

    @Test
    void typedStagedRegistrationCommitsAndRemainsRevocable() {
        TransactionExample.Result result = TransactionExample.run();

        assertTrue(result.generation() >= 0);
        assertTrue(result.published());
        assertTrue(result.revoked());
    }
}
