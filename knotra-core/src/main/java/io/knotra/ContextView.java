package io.knotra;


/** 单个 Context 的只读能力视图。 */
public interface ContextView extends CapabilityLookup {
    String contextId();

    ContextInfo info();
}
