package io.knotra.internal;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
    final Map<String, MountIntent> mounts = new HashMap<>();
    final Map<String, ComponentRuntime> componentRuntimes = new HashMap<>();
    final Map<String, ConfigUpdate> configs = new HashMap<>();
    final Set<String> staleActivations = new LinkedHashSet<>();
    final Map<String, RemovedMount> removedComponents = new java.util.LinkedHashMap<>();
    final Map<String, RemovedMount> reportedRemovedMounts = new java.util.LinkedHashMap<>();
    final Set<String> resetAutoRestart = new LinkedHashSet<>();
    final Set<String> contextDisposals = new LinkedHashSet<>();
    final Set<String> cleanupRetryIntents = new LinkedHashSet<>();
    final Map<String, ProviderLeaseRuntime> retiredRegistrations = new HashMap<>();
    // Publication 槽位效果：新槽位登记共享 ref；终态槽位记录 (ref, 终态数据)，
    // 由 final commit 路径在 published 赋值前统一完成。
    final Map<String, PublicationSlotTerminalRef> createdPublicationSlots =
            new LinkedHashMap<>();
    final Map<String, PublicationTerminalEffect> terminalPublicationSlots =
            new LinkedHashMap<>();
    record ConfigUpdate(Object config, long revision) {
    }

    record RemovedMount(String mountId) {
    }

    record PublicationTerminalEffect(
            PublicationSlotTerminalRef ref,
            PublicationSlotTerminalRef.TerminalData terminalData) {
    }
}
