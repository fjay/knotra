package io.knotra.beans.processor;

/** Knotra 构造器参数的声明类别。 */
enum ParameterKind {
    FIXED("@KnotraFixed"),
    OPTIONAL("@KnotraFixedOptional"),
    DYNAMIC("@KnotraDynamicProxy"),
    CONFIG("@KnotraConfig");

    private final String annotationName;

    ParameterKind(String annotationName) {
        this.annotationName = annotationName;
    }

    String annotationName() {
        return annotationName;
    }
}
