package io.knotra.internal;

import java.util.concurrent.CompletableFuture;

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
    private final CompletableFuture<Void> drain = new CompletableFuture<>();

    ProviderLeaseRuntime(String registrationId) {
        this.registrationId = registrationId;
    }

    String registrationId() {
        return registrationId;
    }

    boolean tryAcquire() {
        synchronized (this) {
            if (retired) {
                return false;
            }
            leases++;
            return true;
        }
    }

    void release() {
        boolean completeDrain;
        synchronized (this) {
            leases--;
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
}
