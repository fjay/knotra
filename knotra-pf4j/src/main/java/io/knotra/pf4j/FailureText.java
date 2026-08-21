package io.knotra.pf4j;

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
