package io.knotra.loader;

import java.util.List;

/**
 * 一次 reconcile 提交的完整期望组件声明树。
 *
 * <p>期望树是声明式的全量状态：Loader 把它与运行时当前状态对比，补挂缺失条目、
 * 释放多余条目、替换实现变化的条目并重配置既有条目。嵌套声明通过
 * {@link ComponentEntry#children()} 表达，条目路径在归一化后构成稳定的树形标识。
 *
 * @param entries 顶层条目声明；null 视为空列表，内容做不可变拷贝
 */
public record ComponentTree(List<ComponentEntry> entries) {

    public ComponentTree {
        entries = List.copyOf(entries == null ? List.of() : entries);
    }

    /** 期望状态为空：下一次 reconcile 会释放 Loader 当前管理的全部条目。 */
    public static ComponentTree empty() {
        return new ComponentTree(List.of());
    }

    /** 由顶层条目构造期望树，嵌套结构由各条目的 children 携带。 */
    public static ComponentTree of(ComponentEntry... entries) {
        return new ComponentTree(List.of(entries));
    }
}
