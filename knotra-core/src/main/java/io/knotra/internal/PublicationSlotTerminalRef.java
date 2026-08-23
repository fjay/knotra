package io.knotra.internal;

import io.knotra.PublicationState;

import java.util.Objects;

/**
 * 一个活跃 Publication 槽位的终态信息载体。
 *
 * <p>ref 在创建新槽位时随草稿一并构造，同一 slotId 的所有句柄共享同一实例。
 * ref 只保存 slotId 与 volatile 终态数据（枚举 + long 代际），不持有 Class、key、
 * value、future 或 handle；槽位进入终态并从活跃视图移除后，ref 随最后一个句柄
 * 一起自然死亡，不进入任何长期注册表。</p>
 *
 * <p>终态提交顺序由协调器保证：所有 prepublish 校验与候选构造成功后，先
 * {@link #completeForCommit(TerminalData)}，再把含该代际的 candidate 赋给
 * {@code published}。读取方按“活跃槽位命中优先，否则读 ref 终态”的顺序观察：
 * 持有旧代际视图的读者仍看到 PUBLISHED（active-first）；读到新代际（槽位已
 * 移除）的读者必然能看到已完成的终态数据。ref 上的完成只允许出现在协调器
 * final commit 路径，普通读取永远不触发完成。</p>
 */
final class PublicationSlotTerminalRef {
    final String slotId;

    private volatile TerminalData terminalData;

    PublicationSlotTerminalRef(String slotId) {
        this.slotId = Objects.requireNonNull(slotId, "slotId");
    }

    /** 协调器 final commit 专用：纯赋值，不得抛出；完成先于 published 赋值。 */
    void completeForCommit(TerminalData data) {
        this.terminalData = Objects.requireNonNull(data, "data");
    }

    TerminalData terminalData() {
        return terminalData;
    }

    /** 终态纯值：只含枚举状态与终态代际，无对象引用。 */
    record TerminalData(PublicationState state, long lastChangedGeneration) {
        TerminalData {
            Objects.requireNonNull(state, "state");
            if (state == PublicationState.PUBLISHED) {
                throw new IllegalArgumentException("terminal state must not be PUBLISHED");
            }
        }
    }
}
