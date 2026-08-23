package io.knotra.it;

import java.util.function.Supplier;

import com.example.integration.contract.IntegrationCoordinator;
import io.knotra.KnotraConfig;
import io.knotra.KnotraRuntime;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

final class KnotraIntegrationExtension implements BeforeEachCallback, AfterEachCallback,
        ParameterResolver, TestExecutionExceptionHandler {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(KnotraIntegrationExtension.class);
    private static final String RUNTIME = "runtime";
    private static final String FAILURE = "testFailure";

    private final Supplier<KnotraConfig> config;
    private final boolean automaticRuntimeClose;

    private KnotraIntegrationExtension(
            Supplier<KnotraConfig> config,
            boolean automaticRuntimeClose) {
        if (config == null) {
            throw new NullPointerException("config supplier");
        }
        this.config = config;
        this.automaticRuntimeClose = automaticRuntimeClose;
    }

    static KnotraIntegrationExtension defaults() {
        return withConfig(KnotraConfig::defaults);
    }

    static KnotraIntegrationExtension withConfig(Supplier<KnotraConfig> config) {
        return new KnotraIntegrationExtension(config, true);
    }

    static KnotraIntegrationExtension manualRuntimeClose() {
        return new KnotraIntegrationExtension(KnotraConfig::defaults, false);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        IntegrationCoordinator.reset();
        IntegrationCoordinator.clearLoaders();
        KnotraConfig effectiveConfig = config.get();
        if (effectiveConfig == null) {
            throw new NullPointerException("runtime config supplier returned null");
        }
        context.getStore(NAMESPACE).put(RUNTIME, KnotraRuntime.create(effectiveConfig));
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable)
            throws Throwable {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        if (store.get(FAILURE) == null) {
            store.put(FAILURE, throwable);
        }
        throw throwable;
    }

    @Override
    public boolean supportsParameter(
            ParameterContext parameterContext,
            ExtensionContext extensionContext) throws ParameterResolutionException {
        return parameterContext.getParameter().getType().equals(KnotraRuntime.class);
    }

    @Override
    public Object resolveParameter(
            ParameterContext parameterContext,
            ExtensionContext extensionContext) throws ParameterResolutionException {
        KnotraRuntime runtime = extensionContext.getStore(NAMESPACE)
                .get(RUNTIME, KnotraRuntime.class);
        if (runtime == null) {
            throw new ParameterResolutionException("runtime has not been created for this test");
        }
        return runtime;
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        KnotraRuntime runtime = store.get(RUNTIME, KnotraRuntime.class);
        Throwable testFailure = store.get(FAILURE, Throwable.class);
        Exception cleanupFailure = null;
        try {
            IntegrationTestKit.drainIntegrations();
        } catch (Exception error) {
            cleanupFailure = error;
        }
        if (automaticRuntimeClose && runtime != null) {
            try {
                runtime.close();
            } catch (Exception error) {
                if (cleanupFailure == null) {
                    cleanupFailure = error;
                } else {
                    cleanupFailure.addSuppressed(error);
                }
            }
        }
        if (cleanupFailure != null) {
            if (testFailure == null) {
                throw cleanupFailure;
            }
            testFailure.addSuppressed(cleanupFailure);
        }
    }
}
