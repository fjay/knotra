package io.knotra.events;

/**
 * 单个监听失败的稳定诊断值。只保留订阅定位信息与经过安全处理的错误文本，不携带 Throwable，
 * 因此不会延长监听实现或 artifact ClassLoader 的生命周期。
 *
 * @param subscriptionId 失败监听所属的订阅标识
 * @param eventName 事件名
 * @param eventTypeName 事件类型的 JVM 全限定名
 * @param mode 分发模式
 * @param message 有界且稳定的错误描述
 */
public final record EventFailure(
        String subscriptionId,
        String eventName,
        String eventTypeName,
        EventMode mode,
        String message) {
}
