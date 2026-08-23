package io.knotra.internal;

import io.knotra.FailureInfo;
import io.knotra.FailurePhase;
import io.knotra.KnotraConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/** 从任意 Throwable 安全构造不可变 FailureInfo 的唯一工厂。 */
public final class FailureCapture {
    private FailureCapture() {
    }

    /** 捕获异常详情；所有防御性读取失败时返回稳定的占位诊断。 */
    public static FailureInfo capture(
            Throwable error,
            FailurePhase phase,
            KnotraConfig.FailureDetailPolicy policy,
            Instant occurredAt) {
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(policy, "policy");
        try {
            return captureSafely(error, phase, policy, occurredAt);
        } catch (Throwable ignored) {
            return new FailureInfo(
                    phase,
                    "<capture failed>",
                    "<failure description unavailable>",
                    List.of(),
                    List.of(),
                    occurredAt == null ? Instant.now() : occurredAt);
        }
    }

    private static FailureInfo captureSafely(
            Throwable input,
            FailurePhase phase,
            KnotraConfig.FailureDetailPolicy policy,
            Instant occurredAt) {
        Throwable current = unwrap(input);
        IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
        seen.put(current, Boolean.TRUE);

        List<FailureInfo.FailureCause> causes = new ArrayList<>();
        Throwable cause = safeCause(current);
        int causeCount = 0;
        while (cause != null && causeCount < policy.maxCauses()) {
            if (seen.put(cause, Boolean.TRUE) != null) {
                break;
            }
            causes.add(new FailureInfo.FailureCause(
                    safeThrowableText(safeClassName(cause), policy.maxTextLength()),
                    safeThrowableText(safeMessage(cause), policy.maxTextLength())));
            cause = safeCause(cause);
            causeCount++;
        }

        return new FailureInfo(
                phase,
                safeThrowableText(safeClassName(current), policy.maxTextLength()),
                safeThrowableText(safeMessage(current), policy.maxTextLength()),
                causes,
                safeFrames(current, policy),
                occurredAt == null ? Instant.now() : occurredAt);
    }

    private static List<String> safeFrames(
            Throwable error,
            KnotraConfig.FailureDetailPolicy policy) {
        if (!policy.includeStackTraces()) {
            return List.of();
        }
        StackTraceElement[] elements;
        try {
            elements = error.getStackTrace();
        } catch (Throwable ignored) {
            return List.of();
        }
        if (elements == null) {
            return List.of();
        }
        List<String> frames = new ArrayList<>();
        for (int index = 0; index < elements.length && frames.size() < policy.maxFrames(); index++) {
            if (elements[index] == null) {
                continue;
            }
            String frame;
            try {
                frame = elements[index].toString();
            } catch (Throwable ignored) {
                continue;
            }
            String safeFrame = safeThrowableText(frame, policy.maxTextLength());
            if (!safeFrame.isBlank()) {
                frames.add(safeFrame);
            }
        }
        return frames;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = safeCause(error);
        if ((error instanceof java.util.concurrent.CompletionException
                || error instanceof java.util.concurrent.ExecutionException)
                && cause != null) {
            return cause;
        }
        return error;
    }

    private static Throwable safeCause(Throwable error) {
        try {
            Throwable cause = error.getCause();
            return cause == error ? null : cause;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safeClassName(Throwable error) {
        try {
            Class<?> type = error.getClass();
            return type == null ? "" : type.getName();
        } catch (Throwable ignored) {
            return "<unknown throwable>";
        }
    }

    private static String safeMessage(Throwable error) {
        try {
            return error.getMessage();
        } catch (Throwable ignored) {
            return "<message unavailable>";
        }
    }

    private static String safeThrowableText(String value, int maxLength) {
        try {
            if (value == null || value.isBlank()) {
                return "";
            }
            String trimmed = value.trim();
            return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
        } catch (Throwable ignored) {
            return "<invalid text>";
        }
    }
}
