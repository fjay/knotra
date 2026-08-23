package io.knotra;

/** 动态调用期间目标 Capability 暂不可用时抛出。 */
public final class CapabilityUnavailableException extends RuntimeException {
    private final CapabilityKey<?> key;

    public CapabilityUnavailableException(String message, CapabilityKey<?> key) {
        super(message);
        this.key = key;
    }

    public CapabilityUnavailableException(String message, CapabilityKey<?> key, Throwable cause) {
        super(message, cause);
        this.key = key;
    }

    /** 触发本次失败的能力键。 */
    public CapabilityKey<?> key() {
        return key;
    }
}
