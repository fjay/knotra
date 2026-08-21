package io.knotra;

import java.util.Objects;

/** 把 Loader、配置文件或插件边界持有的原始值转换为组件的类型化配置。 */
@FunctionalInterface
public interface ConfigDecoder<C> {
    C decode(Object raw) throws Exception;

    /** 创建只接受给定 JVM 类型的 decoder。 */
    static <C> ConfigDecoder<C> typed(Class<C> type) {
        Objects.requireNonNull(type, "type");
        return raw -> type.cast(Objects.requireNonNull(raw, "config"));
    }

    /** 创建无配置 decoder；null 与 NoConfig.INSTANCE 都归一化为 unit value。 */
    static ConfigDecoder<NoConfig> noConfig() {
        return raw -> {
            if (raw == null || raw == NoConfig.INSTANCE) {
                return NoConfig.INSTANCE;
            }
            throw new IllegalArgumentException(
                    "component does not accept configuration: " + raw.getClass().getName());
        };
    }
}
