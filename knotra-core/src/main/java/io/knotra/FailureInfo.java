package io.knotra;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 不可变的结构化异常诊断信息，不持有 Throwable、Class 或 ClassLoader。 */
public record FailureInfo(
        FailurePhase phase,
        String exceptionType,
        String message,
        List<FailureCause> causes,
        List<String> stackTrace,
        Instant occurredAt) {

    /** 结构化诊断专用的空失败详情哨兵常量。 */
    public static final FailureInfo EMPTY = new FailureInfo(
            FailurePhase.SETTLEMENT,
            "",
            "",
            List.of(),
            List.of(),
            Instant.EPOCH);

    public FailureInfo {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(exceptionType, "exceptionType");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(causes, "causes");
        Objects.requireNonNull(stackTrace, "stackTrace");
        Objects.requireNonNull(occurredAt, "occurredAt");
        causes = List.copyOf(causes);
        stackTrace = List.copyOf(stackTrace);
    }

    public String summary() {
        return message.isBlank() ? exceptionType : exceptionType + ": " + message;
    }

    public record FailureCause(String exceptionType, String message) {
        public FailureCause {
            Objects.requireNonNull(exceptionType, "exceptionType");
            Objects.requireNonNull(message, "message");
        }

        public String summary() {
            return message.isBlank() ? exceptionType : exceptionType + ": " + message;
        }
    }
}
