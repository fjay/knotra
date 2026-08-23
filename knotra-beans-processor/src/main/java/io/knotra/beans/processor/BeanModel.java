package io.knotra.beans.processor;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import java.util.List;
import java.util.Optional;

/** 通过完整校验后交给源码渲染器的 Bean 模型。 */
record BeanModel(
        TypeElement type,
        String id,
        TypeMirror configType,
        boolean unmanaged,
        List<ParameterInfo> parameters,
        List<OutputInfo> outputs,
        Optional<ExecutableElement> initializer,
        Optional<ExecutableElement> disposer,
        boolean asyncDisposer,
        Optional<ExecutableElement> normalizer) {
}
