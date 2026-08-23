package io.knotra.loader;


/**
 * 期望条目完成准备后的形态：归一化路径 + 解析出的定义 + 已解码的类型化配置。
 * 只承载 prepared 形态；raw 声明由 DesiredTreePreparer.RawEntry 承载。
 */
record PreparedEntry(
        String path,
        String name,
        ResolvedFactory definition,
        Object typedConfig) {

    /** 补偿回退工厂：把既有受管条目还原成可重新挂载的期望形态。 */
    static PreparedEntry fromManaged(LoaderStateStore.ManagedEntry entry) {
        return new PreparedEntry(entry.path(), entry.name(), entry.definition(), entry.config());
    }
}
