package io.knotra.pf4j;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TickerAgeTest {

    @Test
    void sameTickHasZeroAge() {
        assertEquals(Duration.ZERO, TickerAge.elapsed(7, 7));
        assertEquals(Duration.ZERO, TickerAge.elapsed(-7, -7));
    }

    @Test
    void normalForwardIntervalIsExact() {
        assertEquals(Duration.ofNanos(5), TickerAge.elapsed(10, 15));
    }

    @Test
    void negativeTickerValuesStillMeasureForwardInterval() {
        assertEquals(Duration.ofNanos(5), TickerAge.elapsed(-10, -5));
        assertEquals(Duration.ofNanos(15), TickerAge.elapsed(-10, 5));
    }

    @Test
    void wrapAroundLongMaxIsMeasuredByTwoComplementSubtraction() {
        // 真实间隔 6 纳秒，tick 从 Long.MAX_VALUE 回绕到 Long.MIN_VALUE + 5。
        assertEquals(Duration.ofNanos(6), TickerAge.elapsed(Long.MAX_VALUE, Long.MIN_VALUE + 5));
    }

    @Test
    void maximalRepresentableForwardIntervalIsExact() {
        // 真实间隔 2^63-1 纳秒是 two's-complement 可表示的上界。
        assertEquals(Duration.ofNanos(Long.MAX_VALUE), TickerAge.elapsed(Long.MIN_VALUE, -1L));
    }

    @Test
    void backwardTickIsClampedToZero() {
        assertEquals(Duration.ZERO, TickerAge.elapsed(15, 10));
        assertEquals(Duration.ZERO, TickerAge.elapsed(-5, -10));
    }

    @Test
    void intervalOfAtLeastTwoToTheSixtyThreeIsIndistinguishableFromBackwardAndClamped() {
        // 真实间隔 >= 2^63 时 two's-complement 结果为负，与回退一样钳到零。
        assertEquals(Duration.ZERO, TickerAge.elapsed(Long.MIN_VALUE, Long.MAX_VALUE));
    }
}
