package io.knotra.loader;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.knotra.ComponentState;
import io.knotra.ContextHandle;
import io.knotra.KnotraRuntime;
import io.knotra.TransactionReceipt;
import io.knotra.NoConfig;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.*;

final class LoaderStructureTest {

    private final KnotraRuntime runtime = KnotraRuntime.create();

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    @Test
    void equivalentNormalizedPathsReuseTheSameHandle() throws Exception {
        FactoryRef ref = FactoryRef.of("alpha");
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, LoaderTestKit.factory("alpha", (context, config) -> {})));
        try {
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry(" /alpha// ", ref, NoConfig.INSTANCE))));
            String first = loader.snapshot().entry("alpha").orElseThrow().handleId();

            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha/", ref, NoConfig.INSTANCE))));
            assertEquals(first, loader.snapshot().entry("alpha").orElseThrow().handleId());
            assertEquals(1, loader.snapshot().entries().size());
        } finally {
            loader.close();
        }
    }

    @Test
    void duplicateNormalizedPathsAreRejectedBeforeMutation() throws Exception {
        FactoryRef ref = FactoryRef.of("alpha");
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, LoaderTestKit.factory("alpha", (context, config) -> {})));
        try {
            var result = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE),
                    LoaderTestKit.entry("/alpha/", ref, NoConfig.INSTANCE)));
            LoaderTestKit.assertRejected(result, LoaderDiagnosticCode.INVALID_TREE);
            assertTrue(loader.snapshot().entries().isEmpty());
            assertTrue(runtime.snapshot().components().isEmpty());
        } finally {
            loader.close();
        }
    }

    @Test
    void sameFactoryCanBeMountedAtMultiplePaths() throws Exception {
        FactoryRef ref = FactoryRef.of("shared");
        var factory = LoaderTestKit.factory("shared", (context, config) -> {});
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, factory));
        try {
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("one", ref, NoConfig.INSTANCE),
                    LoaderTestKit.entry("two", ref, NoConfig.INSTANCE))));
            var one = loader.snapshot().entry("one").orElseThrow();
            var two = loader.snapshot().entry("two").orElseThrow();
            assertNotEquals(one.handleId(), two.handleId());
            assertNotEquals(one.contextId(), two.contextId());
            assertEquals(one.factoryIdentity(), two.factoryIdentity());
        } finally {
            loader.close();
        }
    }

    @Test
    void removalDisposesEntryContextAndHandle() throws Exception {
        FactoryRef ref = FactoryRef.of("alpha");
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, LoaderTestKit.factory("alpha", (context, config) -> {})));
        try {
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.empty()));
            assertTrue(loader.snapshot().entries().isEmpty());
            assertTrue(runtime.snapshot().components().isEmpty());
            assertTrue(runtime.snapshot().contexts().stream()
                    .noneMatch(context -> context.name().equals("alpha")));
        } finally {
            loader.close();
        }
    }

    @Test
    void nestedEntryHasAContextPerPathAndStableMountId() throws Exception {
        FactoryRef ref = FactoryRef.of("alpha");
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, LoaderTestKit.factory("alpha", (context, config) -> {})));
        try {
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(ComponentEntry.of(
                    "alpha",
                    ref,
                    LoaderTestKit.entry("child", ref, NoConfig.INSTANCE)))));
            var parent = loader.snapshot().entry("alpha").orElseThrow();
            var child = loader.snapshot().entry("alpha/child").orElseThrow();
            assertEquals("alpha", parent.mountId());
            assertEquals("alpha/child", child.mountId());
            assertNotEquals(parent.contextId(), child.contextId());
            assertTrue(child.contextPath().endsWith("/alpha/child"));
        } finally {
            loader.close();
        }
    }

    @Test
    void orphanNestedPathIsRejected() throws Exception {
        FactoryRef ref = FactoryRef.of("alpha");
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, LoaderTestKit.factory("alpha", (context, config) -> {})));
        try {
            var result = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha/child", ref, NoConfig.INSTANCE)));
            LoaderTestKit.assertRejected(result, LoaderDiagnosticCode.INVALID_TREE);
            assertTrue(runtime.snapshot().contexts().size() == 1);
        } finally {
            loader.close();
        }
    }

    @Test
    void foreignContextAtDesiredPathIsNotClaimed() throws Exception {
        FactoryRef ref = FactoryRef.of("alpha");
        TransactionReceipt<ContextHandle> foreign = runtime.transact(mutation ->
                mutation.childContext(runtime.root(), "alpha"));
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, LoaderTestKit.factory("alpha", (context, config) -> {})));
        try {
            var result = loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE)));
            LoaderTestKit.assertRejected(result, LoaderDiagnosticCode.CONTEXT_CONFLICT);
            assertTrue(runtime.snapshot().components().isEmpty());
            assertTrue(loader.snapshot().entries().isEmpty());
        } finally {
            loader.close();
        }
    }

    @Test
    void ownedSnapshotContainsOnlyDesiredHandles() throws Exception {
        FactoryRef ref = FactoryRef.of("alpha");
        KnotraLoader loader = KnotraLoader.owned(runtime,
                LoaderTestKit.resolver(ref, LoaderTestKit.factory("alpha", (context, config) -> {})));
        LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));
        assertEquals(1, loader.snapshot().entries().size());
        assertEquals(1, runtime.snapshot().components().size());
        assertEquals(ComponentState.ACTIVE, loader.snapshot().entry("alpha").orElseThrow().state());
        loader.close();
        assertTrue(runtime.snapshot().components().isEmpty());
        assertTrue(runtime.snapshot().contexts().stream()
                .noneMatch(context -> context.contextId().equals(loader.baseContext().contextId())));
        assertEquals(io.knotra.ContextState.ACTIVE, runtime.root().state());
    }

    @Test
    void overCloseDisposesOnlyItsContexts() throws Exception {
        FactoryRef ref = FactoryRef.of("alpha");
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(),
                LoaderTestKit.resolver(ref, LoaderTestKit.factory("alpha", (context, config) -> {})));
        LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                LoaderTestKit.entry("alpha", ref, NoConfig.INSTANCE))));
        loader.close();
        assertTrue(runtime.snapshot().components().isEmpty());
        assertEquals(io.knotra.ContextState.ACTIVE, runtime.root().state());
    }

    @Test
    void compositeResolverUsesFirstMatchInOrder() throws Exception {
        FactoryRef firstRef = FactoryRef.of("first");
        FactoryRef secondRef = FactoryRef.of("second");
        ComponentFactoryResolver composite = CompositeFactoryResolver.of(
                LoaderTestKit.resolver(firstRef, LoaderTestKit.factory("first", (context, config) -> {})),
                LoaderTestKit.resolver(secondRef, LoaderTestKit.factory("second", (context, config) -> {})));
        KnotraLoader loader = KnotraLoader.over(runtime, runtime.root(), composite);
        try {
            LoaderTestKit.assertAccepted(loader.reconcile(ComponentTree.of(
                    LoaderTestKit.entry("one", firstRef, NoConfig.INSTANCE),
                    LoaderTestKit.entry("two", secondRef, NoConfig.INSTANCE))));
            assertEquals("first", loader.snapshot().entry("one").orElseThrow().componentId());
            assertEquals("second", loader.snapshot().entry("two").orElseThrow().componentId());
        } finally {
            loader.close();
        }
    }

    @Test
    void pomDeclaresOnlyCoreAtCompileScope() throws Exception {
        Path pom = Path.of(System.getProperty("basedir"), "pom.xml");
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Files.newInputStream(pom));
        NodeList dependencies = document.getElementsByTagName("dependency");
        List<String> compileGroups = new ArrayList<>();
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            String scope = text(dependency, "scope");
            if (scope == null || scope.isBlank() || scope.equals("compile")) {
                compileGroups.add(text(dependency, "groupId"));
            }
        }
        assertEquals(List.of("io.knotra"), compileGroups);
    }

    private static String text(Element parent, String field) {
        NodeList nodes = parent.getElementsByTagName(field);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
