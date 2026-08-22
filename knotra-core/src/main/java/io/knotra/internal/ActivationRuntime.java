package io.knotra.internal;

import io.knotra.CapabilityKey;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * 一次组件 Activation 的内核侧执行状态。
 *
 * <p>对象由 {@link DefaultKnotraRuntime} 在协调器锁内创建，并被所属的
 * {@link ComponentRuntime} 以 {@code current} 或 {@code failedCleanup} 引用。它在启动前捕获
 * 固定的 {@link RuntimeView.BindingData}，用户 {@code start()} 期间产生的 Capability 注册和子挂载先
 * 保存在这里，提交验证成功后才进入 {@link RuntimeView}。{@link LifecycleScopeImpl} 的根作用域
 * 也归属于本对象，负责回滚和正常释放时清理 Activation 创建的可逆资源。</p>
 */
final class ActivationRuntime {
    final String activationId;
    final ComponentRuntime owner;
    final Object config;
    final long configRevision;
    // 本 Activation 固定使用的 BindingSet；注册身份变化会使候选 stale，而不是在启动中途换绑。
    final Map<String, RuntimeView.BindingData> bindings;
    // 与 BindingSet 同一代际捕获的 Capability 值，供用户 start() 在协调器锁外读取。
    final Map<String, Object> capturedValues = new ConcurrentHashMap<>();
    // DYNAMIC 不进入固定 BindingSet；required 只记录启动候选验证所需的初始 presence。
    final Map<String, Boolean> initialDynamicRequiredPresence = new ConcurrentHashMap<>();
    // Activation 的根 LifecycleScope；无论提交成功还是回滚，都负责逆序释放已接受资源。
    final LifecycleScopeImpl scope;
    // 动态调用准入；在 LifecycleScope teardown 前关闭并等待在途调用归零。
    final DynamicCallGate dynamicCalls = new DynamicCallGate();
    // 尚未发布的注册暂存；只有提交验证通过后才复制进 RuntimeView。
    final Map<String, RuntimeView.RegistrationData> stagedRegistrations =
            new ConcurrentHashMap<>();
    final List<ChildMountPlan> childPlans;
    // stale 是提交裁决信号：结构事务可把它从锁外的用户 start() 中召回并按最新代际重启。
    final AtomicBoolean stale = new AtomicBoolean();
    // start() 返回后置位，防止组件保存的 ActivationContext 在事务外继续暂存副作用。
    final AtomicBoolean closed = new AtomicBoolean();

    ActivationRuntime(
            String activationId,
            ComponentRuntime owner,
            Object config,
            long configRevision,
            Map<String, RuntimeView.BindingData> bindings,
            List<ChildMountPlan> childPlans) {
        this.activationId = activationId;
        this.owner = owner;
        this.config = config;
        this.configRevision = configRevision;
        this.bindings = Map.copyOf(bindings);
        this.scope = LifecycleScopeImpl.root(activationId);
        this.childPlans = List.copyOf(childPlans);
    }

    void markStale() {
        stale.set(true);
    }

    RuntimeView.RegistrationData stage(
            CapabilityKey<?> key,
            Object value) {
        String id = Sequences.registration();
        RuntimeView.RegistrationData registration = new RuntimeView.RegistrationData(
                id,
                key,
                owner.contextId,
                new RuntimeView.OwnerData.Activation(activationId),
                value,
                new ProviderLeaseRuntime(id));
        stagedRegistrations.put(key.name(), registration);
        return registration;
    }
}
