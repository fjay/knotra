package io.knotra;

/**
 * 某一已提交运行时代际中类型化的能力注册只读凭据。
 *
 * <p>注册变更统一通过所属 {@link Publication} 的 update/unpublish 完成。</p>
 *
 * @param <T> 能力接口类型
 */
public interface Registration<T> extends RegistrationHandle, Settlement {

    /** 所注册能力的键。 */
    CapabilityKey<T> key();

    /** 注册所在的上下文节点。 */
    ContextHandle context();

}
