package io.knotra.internal;

import java.util.List;
import java.util.Set;

/**
 * 一次 Activation 裁决的最终发布候选。
 *
 * <p>候选在 prepublish 阶段于协调器内构造：视图草稿、执行索引草稿、dirty 过渡
 * 预约、owner/activation 效果与提交后效果全部冻结在此。构造期间除可取消的
 * transition 预约外不得修改任何 live 可变对象；{@link #nextState()} 是唯一的
 * final publish 计算点，调用方连同 {@link #base()} 一起提交给状态存储即为不可逆提交点。</p>
 *
 * <p>实例生命周期仅限于单次提交：发布与效果 apply 完成后即可丢弃，不进入公开
 * 快照或长期故障诊断。</p>
 */
final class ActivationCommitCandidate {
    private final RuntimeView.Draft draft;
    private final KernelStateDraft indexDraft;
    private final PublishedKernelState base;
    private final TransitionPlan transitionPlan;
    private final ActivationOwnerEffect ownerEffect;
    private final ActivationPostCommitEffects postCommitEffects;
    private final Set<String> staleActivations;
    private final boolean abortedCandidate;
    private final String emergencyMessage;
    private final List<ExecutableCommitPlan.PublicationTerminalEffect> publicationTerminals;

    ActivationCommitCandidate(
            RuntimeView.Draft draft,
            KernelStateDraft indexDraft,
            TransitionPlan transitionPlan,
            ActivationOwnerEffect ownerEffect,
            ActivationPostCommitEffects postCommitEffects,
            Set<String> staleActivations,
            boolean abortedCandidate,
            String emergencyMessage,
            ExecutableCommitPlan executable) {
        this.draft = draft;
        this.indexDraft = indexDraft;
        this.base = indexDraft.base();
        this.transitionPlan = transitionPlan;
        this.ownerEffect = ownerEffect;
        this.postCommitEffects = postCommitEffects;
        this.staleActivations = Set.copyOf(staleActivations);
        this.abortedCandidate = abortedCandidate;
        this.emergencyMessage = emergencyMessage;
        this.publicationTerminals = List.copyOf(executable.terminalPublicationSlots.values());
    }

    /** Exact construction baseline required by the kernel state commit. */
    PublishedKernelState base() {
        return base;
    }

    /** 唯一的 final publish 计算点；提交 store 后即为不可逆提交。 */
    PublishedKernelState nextState() {
        return indexDraft.publish(draft.publishOnce());
    }

    /**
     * final commit 专用：在提交 nextState() 前完成全部终态 ref。
     * 纯赋值，不得抛出；prepublish 失败的候选永远不会到达这里。
     */
    void completePublicationTerminals() {
        for (ExecutableCommitPlan.PublicationTerminalEffect effect : publicationTerminals) {
            effect.ref().completeForCommit(effect.terminalData());
        }
    }

    /** final publish 之后在协调器内显式落地 owner/activation 效果；纯赋值，不可抛出。 */
    void applyEffects(ActivationRuntime activation) {
        ownerEffect.apply(activation);
        for (String activationId : staleActivations) {
            ActivationRuntime impacted = indexDraft.activations().get(activationId);
            if (impacted != null) {
                impacted.markStale();
            }
        }
    }

    TransitionPlan transitionPlan() {
        return transitionPlan;
    }

    ActivationPostCommitEffects postCommitEffects() {
        return postCommitEffects;
    }

    boolean abortedCandidate() {
        return abortedCandidate;
    }

    String emergencyMessage() {
        return emergencyMessage;
    }
}
