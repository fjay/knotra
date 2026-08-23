package io.knotra.loader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.knotra.ContextHandle;
import io.knotra.ContextState;
import io.knotra.KnotraRuntime;
import io.knotra.RuntimeSnapshot;

/**
 * 期望树准备器：在触碰任何结构之前，把声明树完整转换为可执行的 prepared 形态。
 *
 * <p>管道固定为 flatten → validateParents → preflight → resolveFactories →
 * decodeConfigs，每段职责单一；任何阶段产生诊断都会让准备结果为空，保证后续
 * 阶段不会基于半成品树提交结构事务（整批拒绝、现有树不动）。</p>
 */
final class DesiredTreePreparer {

    private final KnotraRuntime runtime;
    private final ContextHandle baseContext;
    private final ComponentFactoryResolver resolver;
    private final LoaderStateStore state;

    DesiredTreePreparer(
            KnotraRuntime runtime,
            ContextHandle baseContext,
            ComponentFactoryResolver resolver,
            LoaderStateStore state) {
        this.runtime = runtime;
        this.baseContext = baseContext;
        this.resolver = resolver;
        this.state = state;
    }

    /** 执行完整准备管道；诊断非空时返回空树。 */
    PreparedTree prepare(
            ComponentTree desired,
            List<LoaderDiagnostic> diagnostics) {
        Map<String, RawEntry> flattened = flatten(desired.entries(), diagnostics);
        if (!diagnostics.isEmpty()) {
            return PreparedTree.EMPTY;
        }
        validateParents(flattened, diagnostics);
        if (!diagnostics.isEmpty()) {
            return PreparedTree.EMPTY;
        }
        preflight(flattened.keySet(), diagnostics);
        if (!diagnostics.isEmpty()) {
            return PreparedTree.EMPTY;
        }
        Map<FactoryRef, ResolvedFactory> definitions = resolveFactories(flattened, diagnostics);
        if (!diagnostics.isEmpty()) {
            return PreparedTree.EMPTY;
        }
        return new PreparedTree(decodeConfigs(flattened, definitions, diagnostics));
    }

    /**
     * 深度优先展平声明树：归一化路径、校验重复与越界。相对单段路径拼接在
     * 父路径之下；rawConfig 原样保留，等待 resolver 提供的 decoder 解释。
     */
    private Map<String, RawEntry> flatten(
            List<ComponentEntry> entries,
            List<LoaderDiagnostic> diagnostics) {
        Map<String, RawEntry> flattened = new LinkedHashMap<>();
        collectEntries(entries, "", flattened, diagnostics);
        return flattened;
    }

    private void collectEntries(
            List<ComponentEntry> entries,
            String parentPath,
            Map<String, RawEntry> flattened,
            List<LoaderDiagnostic> diagnostics) {
        for (ComponentEntry entry : entries) {
            String path;
            try {
                path = normalizePath(entry.path(), parentPath);
            } catch (RuntimeException error) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.INVALID_TREE,
                        entry.path(),
                        LoaderErrors.safe(error)));
                continue;
            }
            if (path.isEmpty()) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.INVALID_TREE,
                        parentPath,
                        "entry path is empty"));
                continue;
            }
            if (!parentPath.isEmpty() && !path.startsWith(parentPath + "/")) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.INVALID_TREE,
                        path,
                        "child path is outside parent: " + parentPath));
                continue;
            }
            if (flattened.containsKey(path)) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.INVALID_TREE,
                        path,
                        "duplicate normalized entry path"));
                continue;
            }
            flattened.put(path, new RawEntry(
                    path,
                    lastSegment(path),
                    entry.factoryRef(),
                    entry.rawConfig()));
            collectEntries(entry.children(), path, flattened, diagnostics);
        }
    }

    /** 校验每个非顶层条目的父路径同样出现在期望树中。 */
    private void validateParents(
            Map<String, RawEntry> flattened,
            List<LoaderDiagnostic> diagnostics) {
        for (String path : flattened.keySet()) {
            String parent = parentPath(path);
            if (!parent.isEmpty() && !flattened.containsKey(parent)) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.INVALID_TREE,
                        path,
                        "parent entry is missing: " + parent));
            }
        }
    }

    /**
     * 结构事务前的冲突预检：确认基础 Context 存活且 ACTIVE，且每个期望路径上的
     * Context 与挂载点要么尚不存在、要么恰好属于本 Loader 的记账。外来结构
     * 一律判为冲突，绝不隐式认领，避免 Loader 释放他人的挂载或 Context。
     */
    private void preflight(
            Set<String> paths,
            List<LoaderDiagnostic> diagnostics) {
        RuntimeSnapshot snapshot = runtime.advanced().snapshot();
        RuntimeSnapshot.ContextSnapshot base = snapshot.contexts().stream()
                .filter(context -> context.contextId().equals(baseContext.contextId()))
                .findFirst()
                .orElse(null);
        if (base == null) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.BASE_UNAVAILABLE,
                    "",
                    "base context does not belong to the runtime"));
            return;
        }
        if (base.state() != ContextState.ACTIVE) {
            diagnostics.add(LoaderDiagnostic.of(
                    LoaderDiagnosticCode.BASE_UNAVAILABLE,
                    "",
                    "base context state is " + base.state()));
            return;
        }

        Map<String, RuntimeSnapshot.ContextSnapshot> byPath = new LinkedHashMap<>();
        for (RuntimeSnapshot.ContextSnapshot context : snapshot.contexts()) {
            byPath.put(context.canonicalPath(), context);
        }
        Map<String, RuntimeSnapshot.MountSnapshot> mounts = new LinkedHashMap<>();
        for (RuntimeSnapshot.MountSnapshot mount : snapshot.mounts()) {
            mounts.put(mount.contextId() + "/" + mount.mountId(), mount);
        }
        String baseCanonical = base.canonicalPath();
        for (String path : paths) {
            String canonical = canonical(baseCanonical, path);
            RuntimeSnapshot.ContextSnapshot existing = byPath.get(canonical);
            ContextHandle local = state.context(path);
            if (existing != null && (local == null || !existing.contextId().equals(local.contextId()))) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.CONTEXT_CONFLICT,
                        path,
                        "canonical context already belongs to another owner: " + canonical));
                continue;
            }
            if (local != null && existing == null) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.CONTEXT_CONFLICT,
                        path,
                        "managed context is no longer present in the runtime"));
                continue;
            }

            LoaderStateStore.ManagedEntry entry = state.entry(path);
            RuntimeSnapshot.MountSnapshot mounted = existing == null
                    ? null
                    : mounts.get(existing.contextId() + "/" + path);
            if (mounted != null
                    && (entry == null || !mounted.handleId().equals(entry.handle().handleId()))) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.CONTEXT_CONFLICT,
                        path,
                        "mount id is already occupied by another component"));
            }
        }
    }

    /** 解析去重后的工厂引用；任何引用失败都会让整棵树被拒绝。 */
    private Map<FactoryRef, ResolvedFactory> resolveFactories(
            Map<String, RawEntry> flattened,
            List<LoaderDiagnostic> diagnostics) {
        Map<FactoryRef, ResolvedFactory> definitions = new LinkedHashMap<>();
        for (RawEntry entry : flattened.values()) {
            if (definitions.containsKey(entry.ref())) {
                continue;
            }
            try {
                Optional<ResolvedFactory> definition = resolver.resolve(entry.ref());
                if (definition.isPresent()) {
                    definitions.put(entry.ref(), definition.get());
                } else {
                    diagnostics.add(LoaderDiagnostic.of(
                            LoaderDiagnosticCode.RESOLUTION_FAILED,
                            entry.path(),
                            "resolver returned no implementation"));
                }
            } catch (RuntimeException error) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.RESOLUTION_FAILED,
                        entry.path(),
                        LoaderErrors.safe(error)));
            }
        }
        return definitions;
    }

    /** 用各自工厂的 decoder 把 raw 配置解码为类型化配置。 */
    private Map<String, PreparedEntry> decodeConfigs(
            Map<String, RawEntry> flattened,
            Map<FactoryRef, ResolvedFactory> definitions,
            List<LoaderDiagnostic> diagnostics) {
        Map<String, PreparedEntry> prepared = new LinkedHashMap<>();
        for (RawEntry candidate : flattened.values()) {
            ResolvedFactory definition = definitions.get(candidate.ref());
            Object config;
            try {
                config = definition.decodeConfig(candidate.rawConfig());
            } catch (Exception error) {
                diagnostics.add(LoaderDiagnostic.of(
                        LoaderDiagnosticCode.CONFIG_INVALID,
                        candidate.path(),
                        LoaderErrors.safe(error)));
                continue;
            }
            prepared.put(candidate.path(), new PreparedEntry(
                    candidate.path(),
                    candidate.name(),
                    definition,
                    config));
        }
        return prepared;
    }

    /** 期望树展平后的 raw 形态：路径归一化完成，配置尚未解码。 */
    record RawEntry(
            String path,
            String name,
            FactoryRef ref,
            Object rawConfig) {
    }

    private static String canonical(String baseCanonical, String path) {
        if (baseCanonical.endsWith("/")) {
            return baseCanonical + path;
        }
        return baseCanonical + "/" + path;
    }

    /** 父路径：不含斜杠时返回空串。 */
    static String parentPath(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }

    /** 路径的最后一段，用作 Context 名称。 */
    static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * 统一路径：trim、反斜杠归一为斜杠、折叠 “.” 与空段、拒绝 “..”。
     * 相对单段路径在存在父路径时拼接到父路径之下，因此 “/alpha”、“alpha/”
     * 与 “ alpha ” 归一化后是同一条目。
     */
    static String normalizePath(String raw, String parentPath) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().replace('\\', '/');
        if (value.isEmpty()) {
            return "";
        }
        boolean absolute = value.startsWith("/");
        String[] parts = value.split("/");
        List<String> segments = new ArrayList<>();
        for (String part : parts) {
            String segment = part == null ? "" : part.trim();
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new IllegalArgumentException("relative parent segments are not supported");
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            return "";
        }
        String normalized = String.join("/", segments);
        if (!absolute && !parentPath.isEmpty() && !normalized.contains("/")) {
            return parentPath + "/" + normalized;
        }
        return normalized;
    }
}
