package io.knotra.internal;

import io.knotra.Publication;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Publication 槽位内核化测试共享的包内断言。 */
final class PublicationSlotAssertions {
    private PublicationSlotAssertions() {
    }

    /** 两个并发 publish 得到的句柄必须指向同一 slotId。 */
    static void assertSameSlot(
            DefaultKnotraRuntime runtime,
            Publication<?> first,
            Publication<?> second) {
        assertEquals(slotIdOf(first), slotIdOf(second));
        RuntimeView.PublicationSlotData slot =
                runtime.publicationSlot(slotIdOf(first));
        // 视图为 active-only：存在即 PUBLISHED。
        assertTrue(slot != null);
    }

    /** 并发 update 后槽位 epoch 必须按成功次数单调推进。 */
    static void assertEpochMonotonic(
            DefaultKnotraRuntime runtime,
            Publication<?> publication,
            long expectedEpoch) {
        RuntimeView.PublicationSlotData slot =
                runtime.publicationSlot(slotIdOf(publication));
        assertTrue(slot != null, "slot must remain published");
        assertEquals(expectedEpoch, slot.epoch());
    }

    /** 终态槽位只允许 String/long/enum 字段：不持有 Class、value、handle 或 future。 */
    static void assertPureStringStructure(RuntimeView.PublicationSlotData slot) {
        for (Field field : RuntimeView.PublicationSlotData.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            Class<?> type = field.getType();
            assertTrue(
                    type == String.class
                            || type == long.class
                            || type.isEnum(),
                    () -> "publication slot field " + field.getName()
                            + " must be string/long/enum but was " + type);
        }
        assertEquals(
                Arrays.stream(RuntimeView.PublicationSlotData.class.getRecordComponents())
                        .count(),
                Arrays.stream(RuntimeView.PublicationSlotData.class.getDeclaredFields())
                        .filter(field -> !field.isSynthetic())
                        .count());
    }

    private static String slotIdOf(Publication<?> publication) {
        assertTrue(publication instanceof PublicationImpl<?>);
        return ((PublicationImpl<?>) publication).slotId();
    }
}
