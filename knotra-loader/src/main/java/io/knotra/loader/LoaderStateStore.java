package io.knotra.loader;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.knotra.ContextHandle;
import io.knotra.MountHandle;

/**
 * Loader 的记账与发布存储：稳定路径到受管条目 / Context 的映射，以及发布给
 * {@code snapshot()} 的不可变视图。
 *
 * <p>记账映射只在协调器线程上变更；视图把条目、Context、关闭标记与最近诊断
 * 折叠为同一代不可变状态，通过一次 volatile 读发布，杜绝并发发布期间读到
 * “新条目 + 旧诊断”的撕裂组合。</p>
 */
final class LoaderStateStore {

    /** 稳定路径 → 受管条目（句柄、定义、配置）记账，仅在协调器线程上变更。 */
    private final TreeMap<String, ManagedEntry> current = new TreeMap<>();
    /** 稳定路径 → Loader 创建的 Context 记账，仅在协调器线程上变更。 */
    private final TreeMap<String, ContextHandle> contexts = new TreeMap<>();
    private volatile List<LoaderDiagnostic> diagnostics = List.of();
    private volatile boolean closed;
    /** 发布给 snapshot() 的不可变视图；一次 volatile 读即可得到代际一致的状态。 */
    private volatile LoaderView view = LoaderView.EMPTY;

    LoaderView view() {
        return view;
    }

    boolean isClosed() {
        return closed;
    }

    void markClosed() {
        closed = true;
    }

    boolean hasEntry(String path) {
        return current.containsKey(path);
    }

    boolean hasContext(String path) {
        return contexts.containsKey(path);
    }

    ManagedEntry entry(String path) {
        return current.get(path);
    }

    ContextHandle context(String path) {
        return contexts.get(path);
    }

    /** 按稳定路径排序的 Context 路径快照，供协调器线程内迭代使用。 */
    List<String> contextPaths() {
        return List.copyOf(contexts.keySet());
    }

    void put(ManagedEntry entry) {
        current.put(entry.path(), entry);
    }

    void putContext(String path, ContextHandle context) {
        contexts.put(path, context);
    }

    void putContexts(Map<String, ContextHandle> values) {
        contexts.putAll(values);
    }

    void remove(String path) {
        current.remove(path);
    }

    void removeContext(String path) {
        contexts.remove(path);
    }

    /** 依据运行时存活集合同步记账：移除已消失的 Context 与句柄。 */
    void pruneDisposed(Set<String> liveContextIds, Set<String> liveHandleIds) {
        contexts.keySet().removeIf(path ->
                !liveContextIds.contains(contexts.get(path).contextId()));
        current.keySet().removeIf(path ->
                !liveHandleIds.contains(current.get(path).handle().handleId()));
    }

    /** 从记账中移除该路径及其子树；Context 释放会级联处置全部子孙。 */
    void prune(String rootPath) {
        if (rootPath.isEmpty()) {
            current.clear();
            contexts.clear();
            return;
        }
        String prefix = rootPath + "/";
        current.keySet().removeIf(path -> path.equals(rootPath) || path.startsWith(prefix));
        contexts.keySet().removeIf(path -> path.equals(rootPath) || path.startsWith(prefix));
    }

    void clear() {
        current.clear();
        contexts.clear();
    }

    /** 记录一次成功挂载并发布新视图。 */
    ManagedEntry register(MountAttempt entry) {
        contexts.put(entry.path(), entry.context());
        ManagedEntry managed = new ManagedEntry(
                entry.path(),
                entry.name(),
                entry.context(),
                entry.handle(),
                entry.definition(),
                entry.config());
        current.put(entry.path(), managed);
        republish();
        return managed;
    }

    /** 排序并保存诊断，随后发布包含最新记账的完整视图。 */
    void publish(List<LoaderDiagnostic> values) {
        diagnostics = List.copyOf(values).stream().sorted().toList();
        republish();
    }

    /** 用最近一次诊断与当前记账重发视图，供协调中途让快照跟上结构变化。 */
    void republish() {
        view = new LoaderView(
                closed,
                Map.copyOf(current),
                Map.copyOf(contexts),
                diagnostics);
    }

    /** Loader 对单个受管路径的记账：Context、句柄、当前定义与归一化配置。 */
    static final class ManagedEntry {
        private final String path;
        private final String name;
        private final ContextHandle context;
        private final MountHandle handle;
        private final ResolvedFactory definition;
        private final Object config;

        ManagedEntry(
                String path,
                String name,
                ContextHandle context,
                MountHandle handle,
                ResolvedFactory definition,
                Object config) {
            this.path = path;
            this.name = name;
            this.context = context;
            this.handle = handle;
            this.definition = definition;
            this.config = config;
        }

        String path() {
            return path;
        }

        String name() {
            return name;
        }

        ContextHandle context() {
            return context;
        }

        MountHandle handle() {
            return handle;
        }

        ResolvedFactory definition() {
            return definition;
        }

        Object config() {
            return config;
        }

        ManagedEntry withHandle(MountHandle replacement) {
            return new ManagedEntry(path, name, context, replacement, definition, config);
        }

        ManagedEntry withDefinitionAndConfig(
                ResolvedFactory replacementDefinition,
                Object replacementConfig) {
            return new ManagedEntry(path, name, context, handle, replacementDefinition,
                    replacementConfig);
        }
    }

    /** 发布给 snapshot() 的不可变状态视图，条目与诊断来自同一代发布。 */
    record LoaderView(
            boolean closed,
            Map<String, ManagedEntry> entries,
            Map<String, ContextHandle> contexts,
            List<LoaderDiagnostic> diagnostics) {

        private static final LoaderView EMPTY =
                new LoaderView(false, Map.of(), Map.of(), List.of());
    }
}
