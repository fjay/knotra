package io.knotra.events;

final class EventFailureText {
    private static final int MAX_LENGTH = 512;

    private EventFailureText() {
    }

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
