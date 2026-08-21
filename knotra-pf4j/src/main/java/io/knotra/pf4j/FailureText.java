package io.knotra.pf4j;

/**
 * 把异常压缩为稳定文本的工具，避免诊断和 Snapshot 持有 Throwable 或插件类。
 */
final class FailureText {

    private FailureText() {
    }

    static String describe(Throwable failure) {
        String className = failure.getClass().getName();
        try {
            String message = failure.getMessage();
            return message == null || message.isBlank()
                    ? className
                    : className + ": " + message;
        } catch (Throwable ignored) {
            return className;
        }
    }
}
