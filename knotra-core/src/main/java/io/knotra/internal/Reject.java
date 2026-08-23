package io.knotra.internal;

import io.knotra.RuntimeDiagnostic;

/** 事务草稿校验失败的内部控制流异常。 */
final class Reject extends RuntimeException {
    private final RuntimeDiagnostic diagnostic;

    Reject(RuntimeDiagnostic diagnostic) {
        super(diagnostic.message(), null, false, false);
        this.diagnostic = diagnostic;
    }

    RuntimeDiagnostic diagnostic() {
        return diagnostic;
    }
}
