package io.knotra.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class DocsVocabularyTest {

    private static final List<String> REMOVED_IDENTIFIERS = List.of(
            "ComponentHandle",
            "Provided<",
            "runtime.provide(",
            "BeanDefinition<NoConfig",
            "TransactionReceipt<Provided",
            ".components()",
            "dynamicProxyRequired",
            "Beans.required(",
            "Beans.optional(",
            "KnotraRequire");


    @Test
    void userFacingDocsUseTheCurrentVocabulary() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Path document : markdownDocuments()) {
            String text = Files.readString(document, StandardCharsets.UTF_8);
            for (String identifier : REMOVED_IDENTIFIERS) {
                if (text.contains(identifier)) {
                    failures.add(document + " contains removed identifier " + identifier);
                }
            }
            if (text.contains("runtime.transact(")) {
                failures.add(document + " must use runtime.advanced().transact()");
            }
            if (text.contains("所有结构变更都返回")) {
                failures.add(document + " overstates settlement coverage; scope it to publication/registration/transaction operations");
            }
            if (text.contains(".join()")) {
                failures.add(document + " shows an unbounded join; use a bounded await or get(timeout)");
            }
        }
        assertTrue(failures.isEmpty(), "\n" + String.join("\n", failures));
    }

    @Test
    void quickStartSourceStaysOnTheSimpleApi() throws IOException {
        Path source = projectRoot()
                .resolve("knotra-docs-examples/src/test/java/io/knotra/docs/QuickStartExample.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        List<String> forbidden = List.of(
                "io.knotra.AdvancedRuntime",
                "io.knotra.RuntimeTransaction",
                "io.knotra.Registration",
                "io.knotra.RegistrationHandle",
                "io.knotra.NoConfig",
                "io.knotra.ComponentFactory",
                ".join()");
        List<String> failures = forbidden.stream()
                .filter(text::contains)
                .map(identifier -> "QuickStartExample contains " + identifier)
                .toList();
        assertTrue(failures.isEmpty(), "\n" + String.join("\n", failures));

        assertTrue(text.contains("closeAsync()"),
                "QuickStartExample must document the bounded closeAsync production alternative next to try-with-resources");
    }

    @Test
    void readmePairsTryWithResourcesWithBoundedShutdownGuidance() throws IOException {
        String text = Files.readString(projectRoot().resolve("README.md"), StandardCharsets.UTF_8);
        assertTrue(text.contains("try (KnotraRuntime runtime = KnotraRuntime.create())"),
                "README must keep the canonical Quick Start entry");
        assertTrue(text.contains("closeAsync()"),
                "README must pair the Quick Start close() with bounded closeAsync production guidance");
        assertTrue(text.contains("线程模型与生产实践"),
                "README must link the threading guide for bounded shutdown");
    }

    private static List<Path> markdownDocuments() throws IOException {
        Path docs = projectRoot().resolve("docs");
        try (Stream<Path> files = Files.walk(docs)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(path -> !path.toString().contains("/adr/"))
                    .sorted()
                    .toList();
        }
    }

    private static Path projectRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve(".git"))) {
            directory = directory.getParent();
        }
        assertTrue(directory != null, "project root with .git was not found");
        return directory;
    }
}
