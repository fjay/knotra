package io.knotra.events;

import io.knotra.PendingOperationsSnapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录已停用但仍等待已接受分发收敛的订阅，并把这些等待渲染为纯文本诊断。
 *
 * <p>成员资格的检查和更新由 RegisteredSubscription.membershipLock 串行化：
 * 停用路径先在总线写锁内把 active=false 并按 pending 集合建立成员，最后一个 release
 * 再在同一把短锁内移除成员。快照只读取订阅持有的纯元数据，不执行用户代码。</p>
 */
final class SubscriptionDrainTracker {
    private static final int MAX_DETAIL_IDS = 8;

    private final Set<RegisteredSubscription> draining = ConcurrentHashMap.newKeySet();

    /** 只能在 RegisteredSubscription.membershipLock 内调用，且调用前 pending 集合已经非空。 */
    void track(RegisteredSubscription subscription) {
        draining.add(subscription);
    }

    /** 只能在 RegisteredSubscription.membershipLock 内调用。 */
    void untrack(RegisteredSubscription subscription) {
        draining.remove(subscription);
    }

    List<PendingOperationsSnapshot.Operation> operations(long nowNanos) {
        List<PendingOperationsSnapshot.Operation> result = new ArrayList<>();
        for (RegisteredSubscription subscription : draining) {
            List<AcceptedDispatch> pending = subscription.pendingDispatches();
            if (pending.isEmpty()) {
                continue;
            }
            long oldestAccepted = Long.MAX_VALUE;
            List<String> ids = new ArrayList<>(pending.size());
            for (AcceptedDispatch dispatch : pending) {
                oldestAccepted = Math.min(oldestAccepted, dispatch.acceptedNanos());
                ids.add(dispatch.dispatchId());
            }
            result.add(new PendingOperationsSnapshot.Operation(
                    PendingOperationsSnapshot.Kind.EVENT_SUBSCRIPTION_DRAIN,
                    subscription.subscriptionId(),
                    PendingOperationsSnapshot.WaitType.DISPATCH,
                    Duration.ofNanos(Math.max(0L, nowNanos - oldestAccepted)),
                    "pending dispatches=" + ids.size() + " ids=" + renderIds(ids)));
        }
        return result;
    }

    private static String renderIds(List<String> ids) {
        StringBuilder text = new StringBuilder("[");
        int limit = Math.min(ids.size(), MAX_DETAIL_IDS);
        for (int index = 0; index < limit; index++) {
            if (index > 0) {
                text.append(',');
            }
            text.append(ids.get(index));
        }
        if (ids.size() > MAX_DETAIL_IDS) {
            text.append(",+").append(ids.size() - MAX_DETAIL_IDS).append(" more");
        }
        return text.append(']').toString();
    }
}
