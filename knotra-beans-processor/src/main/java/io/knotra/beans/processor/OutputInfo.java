package io.knotra.beans.processor;

import javax.lang.model.type.TypeMirror;

/** @KnotraOutput 在生成模型中的最小描述。 */
record OutputInfo(String name, TypeMirror contract) {
}
