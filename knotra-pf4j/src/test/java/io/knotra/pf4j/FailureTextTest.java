package io.knotra.pf4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FailureTextTest {

    @Test
    void describeUsesDeepestBoundedCause() {
        Throwable failure = new IllegalStateException(
                "outer",
                new IllegalArgumentException("inner"));

        assertEquals(
                "java.lang.IllegalArgumentException: inner",
                FailureText.describe(failure));
    }

    @Test
    void describeStopsOnCauseCycle() {
        Throwable cycle = new IllegalStateException("cycle") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertEquals(
                cycle.getClass().getName() + ": cycle",
                FailureText.describe(cycle));
    }

    @Test
    void describeToleratesThrowingMessageGetter() {
        Throwable failure = new IllegalStateException("unused") {
            @Override
            public String getMessage() {
                throw new IllegalStateException("broken getter");
            }
        };

        assertEquals(
                failure.getClass().getName() + ": <invalid message>",
                FailureText.describe(failure));
    }

    @Test
    void describeTruncatesLongText() {
        String actual = FailureText.describe(new IllegalStateException("x".repeat(600)));

        assertEquals(500, actual.length());
    }
}
