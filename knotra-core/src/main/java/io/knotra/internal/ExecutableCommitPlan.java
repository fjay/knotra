package io.knotra.internal;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;


/**
 * 一个宿主结构事务提交后需要同步的可执行侧效果清单。
 *
 * <p>视图草稿负责代际与可见结构；本对象只记录哪些 {@link ComponentRuntime}、配置、stale
 * Activation 和 Context 处置要在同一协调器临界区内更新。事务被拒绝时对象随之废弃，不会触碰可执行状态。</p>
 */
final class ExecutableCommitPlan {
    final Map<String, DefaultKnotraRuntime.MountIntent<?>> mounts = new HashMap<>();
    final Map<String, ConfigUpdate> configs = new HashMap<>();
    final Set<String> staleActivations = new LinkedHashSet<>();
    final Set<String> removedComponents = new LinkedHashSet<>();
    final Set<String> resetAutoRestart = new LinkedHashSet<>();
    final Set<String> contextDisposals = new LinkedHashSet<>();
    record ConfigUpdate(Object config, long revision) {
    }
}
