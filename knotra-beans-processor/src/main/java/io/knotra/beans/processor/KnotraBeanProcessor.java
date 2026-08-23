package io.knotra.beans.processor;

import io.knotra.beans.annotation.KnotraBean;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 为每个带有 {@link KnotraBean} 注解的顶级类生成不可变的 {@link io.knotra.beans.BeanDefinition} 工厂。
 * 构造函数依赖项数量遵循声明的构造函数，不受手写 Beans DSL 参数数量上限的限制。
 */
@SupportedAnnotationTypes("io.knotra.beans.annotation.KnotraBean")
public final class KnotraBeanProcessor extends AbstractProcessor implements Processor {

    private final Set<String> processedBeans = new HashSet<>();
    private final Map<String, Element> plannedFactories = new LinkedHashMap<>();

    private Elements elements;
    private Types types;
    private ValidationContext context;
    private BeanValidator validator;
    private FactorySourceRenderer sourceRenderer;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        this.elements = processingEnvironment.getElementUtils();
        this.types = processingEnvironment.getTypeUtils();
        Messager messager = processingEnvironment.getMessager();
        this.context = new ValidationContext(elements, types, messager);
        this.validator = new BeanValidator(elements, types, messager);
        this.sourceRenderer = new FactorySourceRenderer(context);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        for (Element element : roundEnvironment.getElementsAnnotatedWith(KnotraBean.class)) {
            if (!(element instanceof TypeElement type)) {
                context.error(element, "@KnotraBean is only supported on classes");
                continue;
            }
            String qualifiedName = type.getQualifiedName().toString();
            if (!processedBeans.add(qualifiedName)) {
                continue;
            }
            processBean(type);
        }
        return false;
    }

    private void processBean(TypeElement type) {
        String factoryName = generatedName(type);
        Element existingFactory = elements.getTypeElement(factoryName);
        Element plannedOwner = plannedFactories.putIfAbsent(factoryName, type);
        if (existingFactory != null) {
            context.error(type, "cannot generate " + factoryName
                    + " because that fully-qualified type already exists");
        } else if (plannedOwner != null) {
            context.error(type, "cannot generate " + factoryName
                    + " because it is already planned for " + plannedOwner.getSimpleName());
        }

        BeanModel model = validator.validate(type);
        if (model == null || existingFactory != null || plannedOwner != null) {
            return;
        }
        writeFactory(model);
    }

    private String generatedName(TypeElement type) {
        return type.getQualifiedName() + "_KnotraFactory";
    }

    private void writeFactory(BeanModel model) {
        TypeElement type = model.type();
        String packageName = elements.getPackageOf(type).getQualifiedName().toString();
        String generatedSimpleName = type.getSimpleName() + "_KnotraFactory";
        String qualifiedName = packageName.isEmpty()
                ? generatedSimpleName
                : packageName + "." + generatedSimpleName;
        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, type);
            try (Writer writer = file.openWriter()) {
                writer.write(sourceRenderer.render(model, packageName, generatedSimpleName));
            }
        } catch (IOException error) {
            context.error(type, "failed to write " + generatedSimpleName + ": " + error.getMessage());
        }
    }
}
