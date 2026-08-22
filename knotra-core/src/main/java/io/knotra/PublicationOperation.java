package io.knotra;

/** PublicationChange 所代表的发布操作类型枚举。 */
public enum PublicationOperation {
    /** 初始发布操作。 */
    PUBLISH,
    /** 更新已有发布插槽的值。 */
    UPDATE,
    /** 主动撤销发布。 */
    UNPUBLISH
}
