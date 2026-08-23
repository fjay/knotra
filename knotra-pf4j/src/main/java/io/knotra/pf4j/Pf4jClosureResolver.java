package io.knotra.pf4j;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.pf4j.CompoundPluginDescriptorFinder;
import org.pf4j.DependencyResolver;
import org.pf4j.JarPluginRepository;
import org.pf4j.ManifestPluginDescriptorFinder;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginDependency;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.pf4j.PropertiesPluginDescriptorFinder;

/**
 * 离线解析 PF4J 依赖闭包。解析发生在任何插件加载之前，缺失、环与版本冲突都能提前失败。
 */
final class Pf4jClosureResolver {

    private final PluginManager pluginManager;

    Pf4jClosureResolver(PluginManager pluginManager) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
    }

    ArtifactClosure resolve(Path targetPath) {
        CompoundPluginDescriptorFinder descriptorFinder = new CompoundPluginDescriptorFinder()
                .add(new PropertiesPluginDescriptorFinder())
                .add(new ManifestPluginDescriptorFinder());
        Map<String, CatalogEntry> repository = scanRepository(targetPath, descriptorFinder);
        CatalogEntry targetEntry = repositoryByPath(targetPath, repository);
        String targetId = targetEntry.descriptor().getPluginId();
        Map<String, CatalogEntry> selected = selectClosure(targetId, repository);
        List<String> loadOrder = resolveOrder(targetId, selected);
        return new ArtifactClosure(targetId, loadOrder, selected);
    }

    private Map<String, CatalogEntry> scanRepository(
            Path targetPath,
            CompoundPluginDescriptorFinder descriptorFinder) {
        Map<String, CatalogEntry> repository = new LinkedHashMap<>();
        Map<Path, CatalogEntry> byPath = new LinkedHashMap<>();
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        // 同时扫描配置 roots、目标所在目录和已加载 wrapper，才能复用依赖并识别同 ID 冲突。
        for (Path root : safePluginRoots()) {
            roots.add(root.toAbsolutePath().normalize());
        }
        roots.add(targetPath.getParent().toAbsolutePath().normalize());
        for (Path root : roots) {
            for (Path path : new JarPluginRepository(root).getPluginPaths()) {
                putRepositoryEntry(
                        repository,
                        byPath,
                        readDescriptor(descriptorFinder, path.toAbsolutePath().normalize()));
            }
        }
        for (PluginWrapper wrapper : pluginManager.getPlugins()) {
            putRepositoryEntry(repository, byPath, new CatalogEntry(
                    wrapper.getPluginPath().toAbsolutePath().normalize(),
                    wrapper.getDescriptor()));
        }

        putRepositoryEntry(
                repository,
                byPath,
                readDescriptor(descriptorFinder, targetPath.toAbsolutePath().normalize()));
        return repository;
    }

    private Map<String, CatalogEntry> selectClosure(
            String targetId,
            Map<String, CatalogEntry> repository) {
        Map<String, CatalogEntry> selected = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        collectClosure(targetId, repository, selected, new LinkedHashSet<>(), missing);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "missing required PF4J dependencies: " + String.join(", ", missing));
        }
        return selected;
    }

    private List<String> resolveOrder(
            String targetId,
            Map<String, CatalogEntry> selected) {
        DependencyResolver.Result resolved = new DependencyResolver(pluginManager.getVersionManager())
                .resolve(selected.values().stream().map(CatalogEntry::descriptor).toList());
        if (resolved.hasCyclicDependency()) {
            throw new IllegalStateException("PF4J dependency cycle contains artifact " + targetId);
        }
        if (resolved.hasNotFoundDependencies()) {
            throw new IllegalStateException(
                    "missing required PF4J dependencies: " + resolved.getNotFoundDependencies());
        }
        if (resolved.hasWrongVersionDependencies()) {
            throw new IllegalStateException(
                    "incompatible PF4J dependencies: " + resolved.getWrongVersionDependencies());
        }
        Set<String> selectedIds = selected.keySet();
        List<String> order = resolved.getSortedPlugins().stream()
                .filter(selectedIds::contains)
                .toList();
        if (order.isEmpty() || !order.getLast().equals(targetId)) {
            throw new IllegalStateException("PF4J dependency order does not end at " + targetId);
        }
        return order;
    }

    private CatalogEntry repositoryByPath(Path targetPath, Map<String, CatalogEntry> repository) {
        Path normalized = targetPath.toAbsolutePath().normalize();
        return repository.values().stream()
                .filter(entry -> entry.path().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "cannot read a valid PF4J descriptor from " + normalized));
    }

    private CatalogEntry readDescriptor(
            CompoundPluginDescriptorFinder finder,
            Path path) {
        try {
            PluginDescriptor descriptor = finder.find(path);
            if (descriptor != null
                    && descriptor.getPluginId() != null
                    && !descriptor.getPluginId().isBlank()
                    && descriptor.getVersion() != null) {
                return new CatalogEntry(path, descriptor);
            }
        } catch (RuntimeException ignored) {
            // 统一落到下方稳定失败，避免把 finder 的各种运行时异常泄漏给宿主。
        }
        throw new IllegalStateException("cannot read a valid PF4J descriptor from " + path);
    }

    private void putRepositoryEntry(
            Map<String, CatalogEntry> repository,
            Map<Path, CatalogEntry> byPath,
            CatalogEntry entry) {
        CatalogEntry byId = repository.putIfAbsent(entry.descriptor().getPluginId(), entry);
        if (byId != null && !sameEntry(byId, entry)) {
            throw ambiguousTarget(entry.descriptor().getPluginId(), """
                    ambiguous PF4J repository entry: id=%s, firstPath=%s, firstVersion=%s, \
                    actualPath=%s, actualVersion=%s\
                    """.formatted(
                    entry.descriptor().getPluginId(),
                    byId.path(),
                    byId.descriptor().getVersion(),
                    entry.path(),
                    entry.descriptor().getVersion()));
        }
        CatalogEntry pathEntry = byPath.putIfAbsent(entry.path(), entry);
        if (pathEntry != null && !sameEntry(pathEntry, entry)) {
            throw ambiguousTarget(entry.descriptor().getPluginId(), """
                    ambiguous PF4J path entry: path=%s, firstId=%s, firstVersion=%s, \
                    actualId=%s, actualVersion=%s\
                    """.formatted(
                    entry.path(),
                    pathEntry.descriptor().getPluginId(),
                    pathEntry.descriptor().getVersion(),
                    entry.descriptor().getPluginId(),
                    entry.descriptor().getVersion()));
        }
    }

    private boolean sameEntry(CatalogEntry left, CatalogEntry right) {
        return left.path().equals(right.path())
                && left.descriptor().getPluginId().equals(right.descriptor().getPluginId())
                && left.descriptor().getVersion().equals(right.descriptor().getVersion());
    }

    private IllegalStateException ambiguousTarget(String id, String message) {
        return new IllegalStateException(message + ", artifactId=" + id);
    }

    private void collectClosure(
            String id,
            Map<String, CatalogEntry> repository,
            Map<String, CatalogEntry> selected,
            Set<String> visiting,
            Set<String> missing) {
        if (!visiting.add(id)) {
            throw new IllegalStateException("PF4J dependency cycle contains artifact " + id);
        }
        CatalogEntry entry = repository.get(id);
        if (entry == null) {
            missing.add(id);
            visiting.remove(id);
            return;
        }
        selected.put(id, entry);
        // optional 依赖只要在仓库中就参与解析；完全缺失时才允许被跳过。
        for (PluginDependency dependency : entry.descriptor().getDependencies()) {
            if (repository.containsKey(dependency.getPluginId()) || !dependency.isOptional()) {
                collectClosure(
                        dependency.getPluginId(),
                        repository,
                        selected,
                        visiting,
                        missing);
            }
        }
        visiting.remove(id);
    }

    private List<Path> safePluginRoots() {
        try {
            List<Path> roots = pluginManager.getPluginsRoots();
            if (roots == null) {
                return List.of();
            }
            return roots.stream().filter(Objects::nonNull).toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    record CatalogEntry(Path path, PluginDescriptor descriptor) {
    }

    record ArtifactClosure(
            String targetId,
            List<String> loadOrder,
            Map<String, CatalogEntry> entries) {

        CatalogEntry entry(String artifactId) {
            CatalogEntry entry = entries.get(artifactId);
            if (entry == null) {
                throw new IllegalStateException("artifact is not in closure: " + artifactId);
            }
            return entry;
        }
    }
}
