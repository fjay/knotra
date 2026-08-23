package io.knotra.beans.processor;

import javax.lang.model.type.TypeMirror;

/** 构造器参数在生成模型中的最小描述。 */
record ParameterInfo(
        ParameterKind kind,
        String name,
        TypeMirror keyType,
        boolean required) {
}
