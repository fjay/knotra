package io.knotra;

/**
 * 单次能力发布操作（PUBLISH / UPDATE / UNPUBLISH）的变更结果与结算观察句柄。
 *
 * @param <T> 能力接口类型
 */
public interface PublicationChange<T> extends Settlement {

    /** 获取本次变更的具体操作类型。 */
    PublicationOperation operation();

    /** 获取操作所关联的稳定发布插槽。 */
    Publication<T> publication();

    /**
     * 获取本次操作创建的新一代注册凭据。
     *
     * <p>仅在 {@code PUBLISH} 或 {@code UPDATE} 操作时非空；{@code UNPUBLISH} 操作时固定返回 {@code null}。</p>
     */
    Registration<T> registration();
}
