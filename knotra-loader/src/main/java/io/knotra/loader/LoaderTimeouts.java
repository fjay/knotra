package io.knotra.loader;

import java.time.Duration;
import java.util.Objects;

/**
 * Loader 级超时集合：受控挂载 settlement、提交后补偿释放、运行时接管释放的
 * 有界等待，以及 Context 释放的短轮询间隔。
 *
 * <p>所有值必须为正；默认值与 Core 内存事务的收敛预期匹配。测试通过包内注入点
 * 缩短等待，避免为覆盖真实 30 秒超时路径而拖慢测试。</p>
 *
 * @param settlement 受控挂载 commit 后的 settlement 等待，也用于 Loader 发起的 Context 事务结算
 * @param recovery settlement 未收敛时释放已提交挂载的有界等待
 * @param runtimeDisposal 运行时整体关闭接管释放时的有界等待
 * @param contextPoll Context 释放状态短轮询间隔
 */
record LoaderTimeouts(
        Duration settlement,
        Duration recovery,
        Duration runtimeDisposal,
        Duration contextPoll) {

    static final LoaderTimeouts DEFAULTS = new LoaderTimeouts(
            AllocatedMountContext.DEFAULT_SETTLEMENT_TIMEOUT,
            AllocatedMountContext.DEFAULT_RECOVERY_TIMEOUT,
            Duration.ofSeconds(30),
            Duration.ofMillis(10));

    LoaderTimeouts {
        settlement = positive(settlement, "settlementTimeout");
        recovery = positive(recovery, "recoveryTimeout");
        runtimeDisposal = positive(runtimeDisposal, "runtimeDisposalTimeout");
        contextPoll = positive(contextPoll, "contextPollInterval");
    }

    /** 仅替换受控挂载相关的两个超时，保留运行时接管与轮询配置。 */
    LoaderTimeouts withMountTimeouts(Duration settlementTimeout, Duration recoveryTimeout) {
        return new LoaderTimeouts(settlementTimeout, recoveryTimeout, runtimeDisposal, contextPoll);
    }

    /** Context 短轮询在 runtimeDisposal 时限内的重试次数，至少保留一次轮询。 */
    long contextPollTicks() {
        long ticks = runtimeDisposal.toNanos() / contextPoll.toNanos();
        return Math.max(1, ticks);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
