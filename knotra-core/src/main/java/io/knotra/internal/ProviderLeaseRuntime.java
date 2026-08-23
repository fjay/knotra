package io.knotra.internal;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * 单个 Capability registration 的 provider 租约。
 *
 * <p>对象只保存 registration ID 和计数，不保存 capability 值或类型。retire 后禁止新租约；
 * 已获取租约归零时完成 drain future。</p>
 */
final class ProviderLeaseRuntime {

    private final String registrationId;
    private int leases;
    private boolean retired;
    private long oldestLeaseNanos;
    private boolean oldestLeasePresent;
    private final CompletableFuture<Void> drain = new CompletableFuture<>();
    private final LongSupplier ticker;

    ProviderLeaseRuntime(String registrationId, LongSupplier ticker) {
        this.registrationId = registrationId;
        this.ticker = ticker;
    }

    String registrationId() {
        return registrationId;
    }

    boolean tryAcquire() {
        synchronized (this) {
            if (retired) {
                return false;
            }
            if (leases == 0) {
                oldestLeaseNanos = ticker.getAsLong();
                oldestLeasePresent = true;
            }
            leases++;
            return true;
        }
    }

    void release() {
        boolean completeDrain;
        synchronized (this) {
            leases--;
            if (leases == 0) {
                oldestLeasePresent = false;
            }
            completeDrain = retired && leases == 0;
        }
        if (completeDrain) {
            drain.complete(null);
        }
    }

    CompletableFuture<Void> retire() {
        boolean completeDrain;
        synchronized (this) {
            retired = true;
            completeDrain = leases == 0;
        }
        if (completeDrain) {
            drain.complete(null);
        }
        return drain;
    }

    boolean isRetired() {
        synchronized (this) {
            return retired;
        }
    }

    LeaseSnapshot pendingSnapshot() {
        synchronized (this) {
            return new LeaseSnapshot(
                    leases, retired, oldestLeasePresent, oldestLeaseNanos);
        }
    }

    // started 独立于计数表达时间戳存在性，避免假设 System.nanoTime 非负。
    record LeaseSnapshot(
            int leases,
            boolean retired,
            boolean started,
            long startNanos) {
    }
}
