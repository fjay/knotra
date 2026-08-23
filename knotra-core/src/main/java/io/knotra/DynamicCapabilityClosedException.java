package io.knotra;

/** 动态能力调用入口已经关闭时抛出。 */
public final class DynamicCapabilityClosedException extends RuntimeException {
    private final String capabilityName;

    public DynamicCapabilityClosedException(String message, String capabilityName) {
        super(message);
        this.capabilityName = capabilityName;
    }

    public DynamicCapabilityClosedException(
            String message,
            String capabilityName,
            Throwable cause) {
        super(message, cause);
        this.capabilityName = capabilityName;
    }

    /** 触发本次失败的 Capability 名称。 */
    public String capabilityName() {
        return capabilityName;
    }
}
