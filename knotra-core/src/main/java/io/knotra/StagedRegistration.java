package io.knotra;

/**
 * 在宿主事务执行期间暂存的能力注册标记。
 *
 * <p>暂存标记仅在事务记录期间具有类型信息；事务提交成功后，该句柄退化为不透明的
 * {@link RegistrationHandle}（可用于后续显式撤销），不会暴露结算等待或替换接口。</p>
 *
 * @param <T> 能力接口类型
 */
public interface StagedRegistration<T> extends RegistrationHandle {

    /** 所暂存能力的键。 */
    CapabilityKey<T> key();

    /** 关联的上下文节点。 */
    ContextHandle context();
}
