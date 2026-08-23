package io.knotra.internal;

/** Activation 提交裁决；工厂方法显式表达布尔组合的语义。 */
record CommitDecision(
        boolean successful,
        boolean staleCandidate,
        boolean suppressCycle,
        String message) {

    static CommitDecision success() {
        return new CommitDecision(true, false, false, "");
    }

    static CommitDecision stale(String message) {
        return new CommitDecision(false, true, false, message);
    }

    static CommitDecision startFailed(String message) {
        return new CommitDecision(false, false, false, message);
    }

    static CommitDecision cycleRejected(String message) {
        return new CommitDecision(false, false, true, message);
    }

    static CommitDecision commitFailed(String message) {
        return new CommitDecision(false, false, false, message);
    }
}
