package io.knotra;

/**
 * 宿主发布的 Capability 注册的句柄。
 *
 * <p>注册由注册身份标识而不是对象相等性；同一 Context 内同一 Capability 名称占用一个槽位，
 * 撤销后其他提供方才能占用。句柄用于在后续事务中撤销该注册；组件 Activation 拥有的注册
 * 随组件生命周期自动撤销，不能直接 revoke。
 */
public interface RegistrationHandle {
    String registrationId();
}
