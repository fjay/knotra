package io.knotra.beans;

import io.knotra.MountFactory;
import io.knotra.NoConfig;

import java.util.List;

/** Internal no-configuration factory used by the runtime's plain mount facade. */
final class NoConfigBeanDefinitionSupport<T>
        extends BeanDefinitionSupport<NoConfig, T>
        implements MountFactory {

    NoConfigBeanDefinitionSupport(
            String componentId,
            List<BeanDependency<?>> dependencies,
            Beans.ConfigExpertCreator<NoConfig, T> creator,
            List<BeanOutput<T, ?>> outputs,
            Beans.Initializer<? super T> initializer,
            BeanDisposal<T> disposal) {
        super(
                componentId,
                NoConfig.class,
                dependencies,
                creator,
                outputs,
                initializer,
                null,
                disposal);
    }
}
