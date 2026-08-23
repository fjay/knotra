package io.knotra.loader;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 完成准备的期望树；paths() 按层级从浅到深排序，保证先父后子处理。 */
record PreparedTree(Map<String, PreparedEntry> entries) {

    static final PreparedTree EMPTY = new PreparedTree(Map.of());

    List<String> paths() {
        return entries.keySet().stream()
                .sorted(Comparator.comparingInt((String path) -> path.split("/").length)
                        .thenComparing(Function.identity()))
                .toList();
    }

    PreparedEntry entry(String path) {
        return entries.get(path);
    }
}
