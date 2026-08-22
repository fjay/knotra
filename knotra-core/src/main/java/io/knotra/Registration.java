package io.knotra;

/**
 * 某一已提交运行时代际中类型化的能力注册凭据。
 *
 * <p>每次替换注册都会生成一个全新的 {@code Registration} 实例，保持历史不可变。</p>
 *
 * @param <T> 能力接口类型
 */
public interface Registration<T> extends RegistrationHandle, Settlement {

    /** 所注册能力的键。 */
    CapabilityKey<T> key();

    /** 注册所在的上下文节点。 */
    ContextHandle context();

    /** 替换当前注册并返回新一代注册凭据。 */
    Registration<T> replace(T value);

    /** 撤销当前注册并返回结算等待句柄。 */
    Settlement revoke();
}
