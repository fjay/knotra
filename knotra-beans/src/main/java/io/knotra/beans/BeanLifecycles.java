package io.knotra.beans;

import io.knotra.AsyncCloseable;
import io.knotra.LifecycleScope;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Bean 清理登记工具：供 knotra-beans 内部与未来注解处理器生成的代码共用。
 *
 * <p>所有方法都是稳定的静态入口，语义与 Core LifecycleScope 一致：
 * AUTO 推断中 {@link AsyncCloseable} 优先于 {@link AutoCloseable}；
 * 自定义清理失败会走 Core 的 FAILED/可重试路径。</p>
 */
public final class BeanLifecycles {

    private BeanLifecycles() {
    }

    /**
     * 按 Bean 实际类型登记 AUTO 清理：
     * {@code AsyncCloseable} → manageAsync，其次 {@code AutoCloseable} → manage，普通对象不登记。
     */
    public static <T> void autoManage(LifecycleScope scope, String description, T bean) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(bean, "bean");
        if (bean instanceof AsyncCloseable async) {
            scope.manageAsync(description, async);
        } else if (bean instanceof AutoCloseable closeable) {
            scope.manage(description, closeable);
        }
    }

    /** 用自定义同步 disposer 登记 Bean 清理；checked 异常转换为失败条目供 retry。 */
    public static <T> void manageSync(
            LifecycleScope scope,
            String description,
            T bean,
            BeanDisposer<? super T> disposer) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(bean, "bean");
        Objects.requireNonNull(disposer, "disposer");
        scope.onClose(description, () -> {
            try {
                disposer.dispose(bean);
            } catch (Exception error) {
                throw new IllegalStateException("bean disposer failed: " + error, error);
            }
        });
    }

    /** 用自定义异步 disposer 登记 Bean 清理；settle 等待返回的 stage 完成。 */
    public static <T> void manageAsync(
            LifecycleScope scope,
            String description,
            T bean,
            AsyncBeanDisposer<? super T> disposer) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(bean, "bean");
        Objects.requireNonNull(disposer, "disposer");
        scope.onCloseAsync(description, () -> {
            try {
                return disposer.disposeAsync(bean);
            } catch (Exception error) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(error);
                return failed;
            }
        });
    }
}
