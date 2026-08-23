package io.knotra.pf4j;

import java.util.IdentityHashMap;

/**
 * 有界提取异常描述的工具，避免诊断和 Snapshot 持有 Throwable 或插件类。
 */
final class FailureText {

    private static final int MAX_CAUSE_DEPTH = 16;
    private static final int MAX_TEXT_LENGTH = 500;

    private FailureText() {
    }

    /**
     * 有界提取异常描述：循环 cause 链、抛错的 getter 与超长文本都不能阻塞适配器。
     */
    static String describe(Throwable failure) {
        try {
            if (failure == null) {
                return "<no error>";
            }
            Throwable current = failure;
            IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
            seen.put(current, Boolean.TRUE);
            for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
                Throwable cause;
                try {
                    cause = current.getCause();
                } catch (Throwable getterError) {
                    break;
                }
                if (cause == null) {
                    break;
                }
                if (seen.put(cause, Boolean.TRUE) != null) {
                    break;
                }
                current = cause;
            }
            String type;
            try {
                type = current.getClass().getName();
            } catch (Throwable getterError) {
                type = "<unknown type>";
            }
            String message;
            try {
                message = current.getMessage();
            } catch (Throwable getterError) {
                message = "<invalid message>";
            }
            String text = message == null || message.isBlank()
                    ? type
                    : type + ": " + truncate(message);
            return truncate(text);
        } catch (Throwable failureDescription) {
            return "<invalid error description>";
        }
    }

    private static String truncate(String value) {
        if (value.length() <= MAX_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_TEXT_LENGTH);
    }
}
