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
}

