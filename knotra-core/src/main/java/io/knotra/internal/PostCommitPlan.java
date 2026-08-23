package io.knotra.internal;

import java.util.List;
import java.util.Set;

/** Activation 裁决发布后仍需同步的可执行侧效果。 */
record PostCommitPlan(
        List<ChildMountPlan> children,
        Set<String> dirty,
        ExecutableCommitPlan executable) {
}
