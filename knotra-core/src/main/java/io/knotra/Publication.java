package io.knotra;

/**
 * 稳定的能力发布逻辑插槽。
 *
 * <p>Publication 跟踪能力注册的演进历史，自身不直接缓存实例对象。
 * 插槽具备明确的生命周期状态转换规则：
 * <ul>
 *   <li>{@code PUBLISHED}：活跃状态，支持重复调用 {@link #update(Object)} 进行原子热更新。</li>
 *   <li>{@code UNPUBLISHED}：主动撤销终态，不可再更新。</li>
 *   <li>{@code DISPLACED}：被外部替换或上下文销毁淘汰的终态。</li>
 * </ul>
 * </p>
 *
 * <p><b>槽位与句柄语义</b>：slot 由 {@code (context, capability key)} 坐标标识，
 * slotId 语义在坐标存续期间保持稳定。{@code runtime.publish} 对同一坐标是
 * get-or-update 语义：命中活跃槽位即原子更新其内容并返回同一逻辑槽位的句柄。
 * 因此不同 handle 对象的 identity 不代表不同 slot——两次 publish 同一坐标返回的
 * 句柄可能不是同一对象，但都指向同一个稳定槽位。终态槽位（UNPUBLISHED /
 * DISPLACED）不会复活：对其调用 {@link #update(Object)} 会被拒绝；同一坐标重新
 * {@code runtime.publish} 会创建全新槽位，旧句柄保持终态不变。</p>
 *
 * @param <T> 能力接口类型
 */
public interface Publication<T> {

    /** 所发布能力的类型化唯一标识键。 */
    CapabilityKey<T> key();

    /** 关联的上下文节点句柄。 */
    ContextHandle context();

    /** 当前发布插槽的状态。 */
    PublicationState state();

    /**
     * 更新插槽中的能力提供实例，原子发布新一代注册并返回本次操作变更。
     *
     * <p>槽位处于终态（UNPUBLISHED / DISPLACED）时抛出
     * {@link io.knotra.TransactionRejectedException}，不会复活旧槽位。
     * 并发 update 按提交总序线性化，全部成功。</p>
     *
     * @param value 新的能力实例
     * @return 包含本次操作结果与结算等待的变更对象
     */
    PublicationChange<T> update(T value);

    /** 主动撤销该发布插槽，撤销为终态且幂等。 */
    PublicationChange<T> unpublish();
}

