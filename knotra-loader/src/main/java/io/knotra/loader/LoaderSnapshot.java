package io.knotra.loader;

import java.util.List;
import java.util.Optional;

import io.knotra.ComponentGoal;
import io.knotra.ComponentState;

/**
 * Loader 状态的不可变数据快照。
 *
 * <p>与 RuntimeSnapshot 规则一致：快照只包含数据（标识、状态、实现身份、
 * 配置代际与最近诊断），不引用存活组件实例或内部机制，持有快照不会阻止
 * 资源回收。可以在协调器之外随时读取，得到的是最近一次发布的一致视图。
 *
 * @param loaderId Loader 实例 ID
 * @param owned 是否为 owned 模式
 * @param baseContextId 基础 Context ID
 * @param closed 是否已提出关闭请求；请求后单调保持 true，close 排队、失败或重试期间均不回退
 * @param entries 受管条目快照，按路径排序
 * @param diagnostics 最近一次操作发布的诊断，已排序
 */
public record LoaderSnapshot(
        String loaderId,
        boolean owned,
        String baseContextId,
        boolean closed,
        List<EntrySnapshot> entries,
        List<LoaderDiagnostic> diagnostics) {

    public LoaderSnapshot {
        if (loaderId == null || loaderId.isBlank()) {
            throw new IllegalArgumentException("loaderId must not be blank");
        }
        if (baseContextId == null || baseContextId.isBlank()) {
            throw new IllegalArgumentException("baseContextId must not be blank");
        }
        entries = List.copyOf(entries).stream()
                .map(entry -> new EntrySnapshot(
                        entry.path(),
                        entry.contextId(),
                        entry.contextPath(),
                        entry.handleId(),
                        entry.mountId(),
                        entry.componentId(),
                        entry.factoryIdentity(),
                        entry.configRevision(),
                        entry.state(),
                        entry.goal()))
                .sorted()
                .toList();
        diagnostics = List.copyOf(diagnostics).stream().sorted().toList();
    }

    /** 按归一化路径查找条目快照。 */
    public Optional<EntrySnapshot> entry(String path) {
        return entries.stream()
                .filter(entry -> entry.path().equals(path))
                .findFirst();
    }

    /**
     * 单个受管条目的数据快照。
     *
     * @param path 条目的归一化路径
     * @param contextId 条目专属 Context 的 ID
     * @param contextPath 该 Context 的规范路径
     * @param handleId 挂载句柄 ID
     * @param mountId 挂载 ID，等于条目路径
     * @param componentId 组件 ID
     * @param factoryIdentity 当前解析到的实现身份
     * @param configRevision 配置代际，每次成功重配置递增
     * @param state 组件当前状态
     * @param goal 组件当前目标
     */
    public record EntrySnapshot(
            String path,
            String contextId,
            String contextPath,
            String handleId,
            String mountId,
            String componentId,
            FactoryIdentity factoryIdentity,
            long configRevision,
            ComponentState state,
            ComponentGoal goal) implements Comparable<EntrySnapshot> {

        /** 按条目路径排序，保证快照输出稳定。 */
        @Override
        public int compareTo(EntrySnapshot other) {
            return path.compareTo(other.path);
        }
    }
}
