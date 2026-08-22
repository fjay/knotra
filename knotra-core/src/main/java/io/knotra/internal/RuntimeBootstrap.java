package io.knotra.internal;

import io.knotra.KnotraConfig;
import io.knotra.KnotraRuntime;

/**
 * 公开运行时门面所使用的内部引导类。
 *
 * <p>保持此类最小化：扩大其可见范围会将内核实现细节泄漏为公开 API。</p>
 */
public final class RuntimeBootstrap {
    private RuntimeBootstrap() {
    }

    public static KnotraRuntime create(KnotraConfig config) {
        return new DefaultKnotraRuntime(config);
    }
}
