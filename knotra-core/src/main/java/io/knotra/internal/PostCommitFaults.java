package io.knotra.internal;

/** Text helpers shared by host and activation postcommit fault convergence. */
final class PostCommitFaults {
    private PostCommitFaults() {
    }

    static String failure(String scope, String stage, Throwable error) {
        return scope + " failed at " + stage + ": "
                + LifecycleScopeImpl.safeError(error);
    }

    static String append(String current, String failure) {
        if (failure == null || failure.isBlank()) {
            return current;
        }
        return current == null ? failure : current + "; " + failure;
    }
}
