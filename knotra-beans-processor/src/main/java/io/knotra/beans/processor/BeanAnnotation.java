package io.knotra.beans.processor;

import javax.lang.model.type.TypeMirror;
import java.util.List;

/** 从 @KnotraBean 与 @KnotraOutput 注解读取出的类级声明。 */
record BeanAnnotation(
        String id,
        TypeMirror configType,
        boolean noConfig,
        boolean unmanaged,
        List<OutputInfo> outputs) {
}
