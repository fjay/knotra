package io.knotra.pf4j;

import java.time.Duration;

/**
 * 单调 tick 的年龄计算。
 *
 * <p>{@link System#nanoTime()} 可能返回负数并会回绕。只要真实间隔小于 2^63 纳秒
 * （约 292 年），{@code nowTick - startTick} 的 two's-complement 减法就是正确结果，
 * 包括跨越 Long.MAX_VALUE 回绕的情况。时钟真实回退与不小于 2^63 的间隔在该表示下
 * 无法区分，统一钳制为 {@link Duration#ZERO}。</p>
 */
final class TickerAge {

    private TickerAge() {
    }

    static Duration elapsed(long startTick, long nowTick) {
        long elapsed = nowTick - startTick;
        return elapsed < 0 ? Duration.ZERO : Duration.ofNanos(elapsed);
    }
}
