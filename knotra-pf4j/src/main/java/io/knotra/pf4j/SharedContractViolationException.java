package io.knotra.pf4j;

/** artifact 违反配置的共享合约身份时抛出的结构化运行时异常。 */
public final class SharedContractViolationException extends RuntimeException {

    public SharedContractViolationException(String message) {
        super(message);
    }
}
