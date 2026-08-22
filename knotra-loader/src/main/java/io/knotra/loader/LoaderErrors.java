package io.knotra.loader;

import java.util.IdentityHashMap;

final class LoaderErrors {

    private static final int MAX_CAUSE_DEPTH = 16;
    private static final int MAX_TEXT_LENGTH = 500;

    private LoaderErrors() {
    }

    /**
     * 有界提取异常描述：恶意或循环的 cause 链、抛错的 getter 与超长文本都不能
     * 阻塞 Loader 协调器。诊断文本不持有 Throwable、Class 或 ClassLoader。
     */
    static String safe(Throwable error) {
        try {
            if (error == null) {
                return "<no error>";
            }
            Throwable current = error;
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
        } catch (Throwable failure) {
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
