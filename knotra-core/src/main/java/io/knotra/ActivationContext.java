package io.knotra;

import java.util.Optional;

/**
 * 组件 {@code start()} 执行期间使用的一次性激活上下文。
 *
 * <p>上下文只在当前 Activation 内有效：启动代码返回后立即关闭；上下文关闭或激活被判定为过期后，
 * 任何方法调用都会失败。启动代码在协调器锁外执行，可以安全地做耗时初始化，但不应长期阻塞。
 *
 * <p>核心语义：
 * <ul>
 *   <li>{@link #find} 与 {@link #require} 只能访问 {@link ComponentDescriptor} 声明的需求，
 *       绑定值来自激活开始时固定的 BindingSet，不受启动期间的结构变化影响；</li>
 *   <li>{@link #provide} 与 {@link #mountChild} 均为暂存操作，属于本次 Activation 的事务边界：
 *       验证成功后原子发布，失败或过期时整体回滚，不会留下部分注册或残余子挂载。</li>
 * </ul>
 */
public interface ActivationContext {
    /**
     * 获取声明的 Capability 在当前 BindingSet 中的绑定值。
     *
     * @throws IllegalArgumentException 该 Capability 未在描述符中声明，或为必需能力但绑定缺失
     */
    <T> T require(CapabilityKey<T> key);

    /**
     * 查询声明的 Capability 绑定。OPTIONAL 需求可能返回空；REQUIRED 需求在激活开始前
     * 已确认可解析，正常情况下不会为空。
     *
     * @throws IllegalArgumentException 该 Capability 未在描述符中声明
     */
    <T> Optional<T> find(CapabilityKey<T> key);

    /**
     * 暂存一个由当前 Activation 拥有的 Capability 注册。
     *
     * <p>注册值必须与 {@code key} 的合约类型匹配，且同一 Capability 名称在 Runtime 生命周期内
     * 绑定唯一的 Java 类型。同一激活内同一名称只能暂存一次。验证成功后注册随激活原子发布，
     * 并在激活释放或被新代际替换时自动撤销。
     */
    <T> void provide(CapabilityKey<T> key, T value);

    /**
     * 暂存一个由当前 Activation 拥有的子组件挂载。
     *
     * <p>子组件挂载到与当前组件相同的 Context，{@code mountId} 必须在该 Context 内唯一；
     * 激活提交后子组件才真正存在，激活回滚时子挂载被整体丢弃。子组件由当前 Activation 拥有，
     * 释放顺序先于父组件。默认继承父组件的来源（origin）。
     */
    <C> ComponentHandle<C> mountChild(
            String mountId,
            ComponentFactory<C> factory,
            C config);

    /** {@link #mountChild(String, ComponentFactory, Object)} 的变体，允许覆盖挂载选项。 */
    <C> ComponentHandle<C> mountChild(
            String mountId,
            ComponentFactory<C> factory,
            C config,
            MountOptions options);

    /** 返回当前 Activation 的根 LifecycleScope，用于登记本次启动申请的可逆资源。 */
    LifecycleScope lifecycle();

    /** 返回挂载 Context 的元数据快照。 */
    ContextInfo contextInfo();
}
