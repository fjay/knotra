package io.knotra.events;

/**
 * 监听失败文本的安全归一化工具。错误对象的 {@code getMessage()} 或 {@code toString()} 也可能抛出异常，
 * 因此诊断提取必须逐步降级并限制长度，保证 Snapshot 和分发结果稳定。
 */
final class EventFailureText {
    private static final int MAX_LENGTH = 512;

    private EventFailureText() {
    }

    /**
     * 提取有界的错误描述。优先使用错误消息，其次使用 {@code toString()}，最后降级到类名；
     * 控制字符会替换为空格，避免诊断文本破坏日志或展示格式。
     *
     * @param error 待描述的错误，可为 {@code null}
     * @return 不超过 512 个字符的安全文本
     */
    static String describe(Throwable error) {
        if (error == null) {
            return "listener failed without an error";
        }
        String message = safe(() -> error.getMessage());
        if (isUsable(message)) {
            return sanitize(message);
        }
        String text = safe(error::toString);
        if (isUsable(text)) {
            return sanitize(text);
        }
        return sanitize(error.getClass().getName());
    }

    private static String safe(MessageSupplier supplier) {
        try {
            return supplier.get();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isUsable(String value) {
        return value != null && !value.isBlank();
    }

    private static String sanitize(String value) {
        int limit = Math.min(value.length(), MAX_LENGTH);
        StringBuilder result = new StringBuilder(limit);
        for (int index = 0; index < limit; index++) {
            char character = value.charAt(index);
            result.append(character >= ' ' && character != 127 ? character : ' ');
        }
        return result.toString();
    }

    private interface MessageSupplier {
        String get() throws Throwable;
    }
}
