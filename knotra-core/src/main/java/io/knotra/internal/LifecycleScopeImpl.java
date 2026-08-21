package io.knotra.internal;

import io.knotra.AsyncDisposer;
import io.knotra.CleanupState;
import io.knotra.LifecycleScope;
import io.knotra.LifecycleState;
import io.knotra.ManagedHandle;
import io.knotra.RuntimeSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Activation 拥有的可逆资源作用域实现。
 *
 * <p>每个 {@link ActivationRuntime} 持有一个根作用域，子作用域和受管条目作为树中的
 * {@link Node} 记录。所有节点使用同一个全局序列，释放时按序列倒序执行，形成跨嵌套层的 LIFO 顺序；
 * 显式 parallelChild 只并行其直接条目，不把并行性扩展到后续兄弟节点。释放是异步聚合的，失败条目
 * 保持 {@code FAILED} 并保留释放器，供 ComponentHandle.retry() 只重试失败部分。</p>
 *
 * <p>锁约定：新增子节点时先锁父再锁子，保证父状态检查与树插入有序；释放器本身在作用域锁外执行。
 * {@link DefaultKnotraRuntime} 先完成依赖方清理，再触发提供方 {@code teardown()}。</p>
 */
final class LifecycleScopeImpl implements LifecycleScope {
    // 节点创建顺序全局唯一；LIFO 不依赖各作用域本地列表的嵌套时机。
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final String id;
    private final LifecycleScopeImpl parent;
    private final String description;
    private final boolean parallel;
    private final long sequence;
    private final Object lock = new Object();
    // 只在各自 lock 内修改；teardown 先复制快照，再在锁外执行释放器。
    private final List<Node> nodes = new ArrayList<>();

    private LifecycleState state = LifecycleState.OPEN;
    // 错误只保留文本，不保存 Throwable，避免诊断路径延长用户对象生命周期。
    private final List<String> cleanupErrors = new ArrayList<>();
    private LifecycleScopeImpl(
            String id,
            LifecycleScopeImpl parent,
            String description,
            boolean parallel,
            long sequence) {
        this.id = id;
        this.parent = parent;
        this.description = description;
        this.parallel = parallel;
        this.sequence = sequence;
    }

    static LifecycleScopeImpl root(String activationId) {
        return new LifecycleScopeImpl(
                "scope-" + activationId,
                null,
                "activation",
                false,
                SEQUENCE.incrementAndGet());
    }

    @Override
    public String scopeId() {
        return id;
    }

    @Override
    public <T extends AutoCloseable> T manage(String description, T resource) {
        Objects.requireNonNull(resource, "resource");
        return manage(description, resource::close, resource, null, false);
    }

    @Override
    public ManagedHandle onClose(String description, Runnable disposer) {
        Objects.requireNonNull(disposer, "disposer");
        return manage(description, disposer::run, null, null, false);
    }

    @Override
    public ManagedHandle manageAsync(String description, AsyncDisposer disposer) {
        Objects.requireNonNull(disposer, "disposer");
        return manage(description, null, null, disposer, true);
    }

    @Override
    public LifecycleScope child(String description) {
        return newChild(description, false);
    }

    @Override
    public LifecycleScope parallelChild(String description) {
        return newChild(description, true);
    }

    @Override
    public LifecycleScope parent() {
        return parent;
    }

    @Override
    public LifecycleState state() {
        synchronized (lock) {
            return state;
        }
    }

    private <T> T manage(
            String description,
            AutoCloseableDisposer sync,
            T resource,
            AsyncDisposer async,
            boolean asynchronous) {
        String safeDescription = safeText(description);
        // 先父后子：父作用域已经停止时，子作用域不能再接受晚到资源。
        synchronized (parent == null ? lock : parent.lock) {
            if (parent != null && parent.state != LifecycleState.OPEN) {
                throw new IllegalStateException(
                        "parent lifecycle scope is not open: " + parent.scopeId());
            }
            synchronized (lock) {
                if (state != LifecycleState.OPEN) {
                    throw new IllegalStateException(
                            "lifecycle scope is not open: " + scopeId());
                }
                Entry entry = new Entry(
                        "entry-" + SEQUENCE.incrementAndGet(),
                        safeDescription,
                        sync,
                        resource,
                        async,
                        asynchronous,
                        SEQUENCE.incrementAndGet());
                nodes.add(entry);
                return resource;
            }
        }
    }

    private LifecycleScope newChild(String description, boolean parallel) {
        String safeDescription = safeText(description);
        synchronized (lock) {
            if (state != LifecycleState.OPEN) {
                throw new IllegalStateException("lifecycle scope is not open: " + scopeId());
            }
            LifecycleScopeImpl child = new LifecycleScopeImpl(
                    "scope-" + SEQUENCE.incrementAndGet(),
                    this,
                    safeDescription,
                    parallel,
                    SEQUENCE.incrementAndGet());
            nodes.add(new ScopeNode(child, SEQUENCE.incrementAndGet()));
            return child;
        }
    }

    boolean isStopping() {
        synchronized (lock) {
            return state != LifecycleState.OPEN;
        }
    }

    String lastCleanupError() {
        List<String> errors = new ArrayList<>();
        collectCleanupErrors(errors);
        return String.join("; ", errors.stream().distinct().toList());
    }

    private void collectCleanupErrors(List<String> result) {
        synchronized (lock) {
            result.addAll(cleanupErrors);
            for (Node node : nodes) {
                if (node instanceof ScopeNode scopeNode
                        && scopeNode.scope().state == LifecycleState.FAILED) {
                    scopeNode.scope().collectCleanupErrors(result);
                }
            }
        }
    }

    // 先关闭整棵树的准入，再异步释放；所有边界完成后按收集时的反序汇总最终状态。
    CompletableFuture<Void> teardown() {
        markStopping();
        List<LifecycleScopeImpl> descendants = descendants();
        return cleanupBoundary(this).whenComplete((ignored, error) -> {
            for (int index = descendants.size() - 1; index >= 0; index--) {
                descendants.get(index).finish();
            }
        });
    }

    // 递归传播 STOPPING 也遵守父到子的方向；该状态会拒绝清理期间的晚到 manage。
    private void markStopping() {
        synchronized (lock) {
            if (state != LifecycleState.OPEN) {
                return;
            }
            state = LifecycleState.STOPPING;
            for (Node node : nodes) {
                if (node instanceof ScopeNode scopeNode) {
                    scopeNode.scope().markStopping();
                }
            }
        }
    }

    private CompletableFuture<Void> cleanupBoundary(LifecycleScopeImpl boundary) {
        List<Node> direct;
        synchronized (boundary.lock) {
            direct = new ArrayList<>(boundary.nodes);
        }
        // 使用全局序列倒序，而不是列表插入顺序，保证嵌套创建也得到确定性的 LIFO。
        direct.sort(Comparator.comparingLong(Node::sequence).reversed());

        // parallel 组只放宽其直接节点的互斥；边界之间仍按父作用域的 LIFO 进入。
        if (boundary.parallel) {
            List<CompletableFuture<Void>> work = direct.stream()
                    .map(boundary::cleanupNode)
                    .toList();
            return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
        }

        // 失败会被转换成已完成的 null，因此串行链继续执行后续兄弟条目而不是短路。
        CompletableFuture<Void> result = CompletableFuture.completedFuture(null);
        for (Node node : direct) {
            result = result.thenCompose(ignored -> boundary.cleanupNode(node));
        }
        return result;
    }

    private CompletableFuture<Void> cleanupNode(Node node) {
        if (node instanceof Entry entry) {
            return runEntry(entry);
        }
        return cleanupBoundary(((ScopeNode) node).scope());
    }

    // 临界区只负责幂等状态迁移；实际同步/异步释放器必须在作用域锁外执行。
    private CompletableFuture<Void> runEntry(Entry entry) {
        synchronized (lock) {
            if (entry.state() == CleanupState.SUCCEEDED) {
                return CompletableFuture.completedFuture(null);
            }
            if (entry.state() == CleanupState.PENDING && !isStopping()) {
                CompletableFuture<Void> rejected = new CompletableFuture<>();
                rejected.completeExceptionally(
                        new IllegalStateException("scope is not stopping"));
                return rejected;
            }
            entry.beginAttempt();
        }
        CompletableFuture<Void> result;
        try {
            if (entry.asynchronous()) {
                CompletionStage<Void> stage = entry.async().dispose();
                result = stage == null
                        ? CompletableFuture.completedFuture(null)
                        : stage.toCompletableFuture();
            } else {
                entry.sync().dispose();
                result = CompletableFuture.completedFuture(null);
            }
        } catch (Throwable error) {
            // 同步失败也走同一结算路径，并返回完成值让串行释放继续。
            settleEntry(entry, error);
            return CompletableFuture.completedFuture(null);
        }
        return result.handle((ignored, error) -> {
            settleEntry(entry, error);
            return null;
        });
    }

    // 成功会清空释放引用；失败保留在 Entry 中，retry 时只重开 FAILED 条目。
    private void settleEntry(Entry entry, Throwable error) {
        synchronized (lock) {
            if (error == null) {
                entry.succeed();
            } else {
                String message = safeError(error);
                entry.fail(message);
                cleanupErrors.add(message);
            }
        }
    }

    // 汇总所有嵌套作用域的失败；任一 Entry 失败都会让该边界保持 FAILED 以便重试。
    private void finish() {
        synchronized (lock) {
            boolean failed = nodes.stream().anyMatch(node -> {
                if (node instanceof Entry entry) {
                    return entry.state() == CleanupState.FAILED;
                }
                LifecycleScopeImpl scope = ((ScopeNode) node).scope();
                synchronized (scope.lock) {
                    if (scope.state != LifecycleState.FAILED) {
                        return false;
                    }
                    String error = String.join("; ", scope.cleanupErrors);
                    if (!error.isBlank() && !cleanupErrors.contains(error)) {
                        cleanupErrors.add(error);
                    }
                    return true;
                }
            });
            state = failed ? LifecycleState.FAILED : LifecycleState.SUCCEEDED;
        }
    }

    private List<LifecycleScopeImpl> descendants() {
        List<LifecycleScopeImpl> result = new ArrayList<>();
        collectDescendants(result);
        return result;
    }

    private void collectDescendants(List<LifecycleScopeImpl> result) {
        result.add(this);
        synchronized (lock) {
            for (Node node : nodes) {
                if (node instanceof ScopeNode scopeNode) {
                    scopeNode.scope().collectDescendants(result);
                }
            }
        }
    }

    List<RuntimeSnapshot.LifecycleScopeSnapshot> snapshots(String activationId) {
        List<RuntimeSnapshot.LifecycleScopeSnapshot> result = new ArrayList<>();
        collectSnapshots(activationId, result);
        return result;
    }

    private void collectSnapshots(
            String activationId,
            List<RuntimeSnapshot.LifecycleScopeSnapshot> result) {
        List<RuntimeSnapshot.ManagedEntrySnapshot> entries;
        synchronized (lock) {
            entries = nodes.stream()
                    .filter(Entry.class::isInstance)
                    .map(Entry.class::cast)
                    .map(entry -> new RuntimeSnapshot.ManagedEntrySnapshot(
                            entry.id(),
                            entry.description(),
                            entry.state(),
                            entry.attempts(),
                            entry.lastError()))
                            .sorted(Comparator.comparing(
                                    RuntimeSnapshot.ManagedEntrySnapshot::entryId))
                    .toList();
            result.add(new RuntimeSnapshot.LifecycleScopeSnapshot(
                    id,
                    parent == null ? null : parent.id,
                    activationId,
                    description,
                    parallel,
                    state,
                    entries));
            for (Node node : nodes) {
                if (node instanceof ScopeNode scopeNode) {
                    scopeNode.scope().collectSnapshots(activationId, result);
                }
            }
        }
    }

    String entryDescription(String entryId) {
        synchronized (lock) {
            return entries().stream()
                    .filter(entry -> entry.id().equals(entryId))
                    .map(Entry::description)
                    .findFirst()
                    .orElse("");
        }
    }

    CleanupState entryState(String entryId) {
        synchronized (lock) {
            return entries().stream()
                    .filter(entry -> entry.id().equals(entryId))
                    .map(Entry::state)
                    .findFirst()
                    .orElse(CleanupState.SUCCEEDED);
        }
    }

    int entryAttempts(String entryId) {
        synchronized (lock) {
            return entries().stream()
                    .filter(entry -> entry.id().equals(entryId))
                    .map(Entry::attempts)
                    .findFirst()
                    .orElse(0);
        }
    }

    String entryLastError(String entryId) {
        synchronized (lock) {
            return entries().stream()
                    .filter(entry -> entry.id().equals(entryId))
                    .map(Entry::lastError)
                    .findFirst()
                    .orElse("");
        }
    }

    private List<Entry> entries() {
        return nodes.stream()
                .filter(Entry.class::isInstance)
                .map(Entry.class::cast)
                .toList();
    }

    private static String safeText(String value) {
        if (value == null) {
            return "";
        }
        try {
            String text = value.isBlank() ? "" : value.trim();
            return text.length() <= 160 ? text : text.substring(0, 160);
        } catch (Throwable error) {
            return "<invalid description>";
        }
    }

    static String safeError(Throwable error) {
        if (error == null) {
            return "cleanup failed";
        }
        if (error instanceof CompletionException completion && completion.getCause() != null) {
            return safeError(completion.getCause());
        }
        String text;
        try {
            text = error.getMessage();
            if (text == null || text.isBlank()) {
                text = error.getClass().getName();
            }
        } catch (Throwable ignored) {
            return "<malformed error message>";
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    @FunctionalInterface
    private interface AutoCloseableDisposer {
        void dispose() throws Exception;
    }

    private sealed interface Node permits Entry, ScopeNode {
        long sequence();
    }

    private record ScopeNode(LifecycleScopeImpl scope, long sequence) implements Node {
    }

    // 单个受管条目；释放器字段只在成功后清空，失败时保留用于 ComponentHandle.retry()。
    private static final class Entry implements Node {
        private final String id;
        private final String description;
        private final boolean asynchronous;
        private final long sequence;
        private AutoCloseableDisposer sync;
        private Object resource;
        private AsyncDisposer async;
        private CleanupState state = CleanupState.PENDING;
        private int attempts;
        private String lastError = "";

        private Entry(
                String id,
                String description,
                AutoCloseableDisposer sync,
                Object resource,
                AsyncDisposer async,
                boolean asynchronous,
                long sequence) {
            this.id = id;
            this.description = description;
            this.sync = sync;
            this.resource = resource;
            this.async = async;
            this.asynchronous = asynchronous;
            this.sequence = sequence;
        }

        private String id() {
            return id;
        }

        private String description() {
            return description;
        }

        private AutoCloseableDisposer sync() {
            return sync;
        }

        private AsyncDisposer async() {
            return async;
        }

        private boolean asynchronous() {
            return asynchronous;
        }

        @Override
        public long sequence() {
            return sequence;
        }

        private CleanupState state() {
            return state;
        }

        private int attempts() {
            return attempts;
        }

        private String lastError() {
            return lastError;
        }

        private void beginAttempt() {
            attempts++;
            state = CleanupState.PENDING;
        }

        private void succeed() {
            state = CleanupState.SUCCEEDED;
            lastError = "";
            sync = null;
            async = null;
            resource = null;
        }

        private void fail(String message) {
            state = CleanupState.FAILED;
            lastError = message;
        }
    }
}
