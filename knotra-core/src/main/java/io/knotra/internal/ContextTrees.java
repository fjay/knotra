package io.knotra.internal;

import java.util.LinkedHashSet;
import java.util.Set;

/** Context 子树的纯拓扑辅助。 */
final class ContextTrees {
    private ContextTrees() {
    }

    /** 过滤同一批请求中被祖先覆盖的嵌套 Context 处置。 */
    static Set<String> outermostDisposals(RuntimeView current, Set<String> requested) {
        Set<String> result = new LinkedHashSet<>();
        for (String candidate : requested) {
            boolean covered = requested.stream().anyMatch(ancestor ->
                    !ancestor.equals(candidate)
                            && current.contexts.containsKey(ancestor)
                            && current.contexts.get(candidate) != null
                            && current.isInSubtree(candidate, ancestor));
            if (!covered) {
                result.add(candidate);
            }
        }
        return result;
    }
}
