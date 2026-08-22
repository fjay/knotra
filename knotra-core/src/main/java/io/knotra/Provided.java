package io.knotra;

import java.util.concurrent.CompletionStage;

/**
 * 宿主发布的类型化 Capability 注册句柄。
 *
 * <p>句柄绑定创建它的 CapabilityKey 与所属 Runtime。{@link #replace(Object)} 在一个结构事务内
 * 撤销当前注册并发布新值；提交同步完成，但不阻塞下游 drain 或重新激活，收敛进度通过
 * {@link #whenSettled()} 观察。替换返回新的注册身份，旧句柄随之失效。</p>
 */
public interface Provided<T> extends RegistrationHandle {
    /** 创建该注册的 Capability 合约。 */
    CapabilityKey<T> key();

    /** 创建或替换该注册的事务 settlement。 */
    CompletionStage<Void> whenSettled();

    /**
     * 原子替换当前注册并返回新的类型化句柄。
     *
     * <p>调用后当前句柄失效；后续 replace/revoke 会按结构事务语义拒绝。</p>
     */
    Provided<T> replace(T value);

    /** 撤销当前注册；重复撤销会按结构事务语义拒绝。 */
    void revoke();
}
