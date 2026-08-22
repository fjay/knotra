package io.knotra.beans.processor;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** JavaCompiler-based annotation processor test kit. */
final class CompilerKit {

    record Compilation(
            Path classes,
            Path generatedSources,
            List<Diagnostic<? extends JavaFileObject>> diagnostics) {

        List<String> generatedFiles() throws IOException {
            if (!Files.isDirectory(generatedSources)) {
                return List.of();
            }
            try (Stream<Path> files = Files.walk(generatedSources)) {
                return files
                        .filter(path -> path.toString().endsWith(".java"))
                        .map(path -> {
                            try {
                                return Files.readString(path, StandardCharsets.UTF_8);
                            } catch (IOException error) {
                                throw new AssertionError(error);
                            }
                        })
                        .toList();
            }
        }

        String generatedSource(String fileName) throws IOException {
            try (Stream<Path> files = Files.walk(generatedSources)) {
                Path file = files
                        .filter(path -> path.getFileName().toString().equals(fileName))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("generated source not found: " + fileName));
                return Files.readString(file, StandardCharsets.UTF_8);
            }
        }

        URLClassLoader classLoader() throws IOException {
            return new URLClassLoader(
                    new URL[] {classes.toUri().toURL()},
                    Thread.currentThread().getContextClassLoader());
        }
    }

    record Source(String qualifiedName, String text) {
        JavaFileObject toFileObject() {
            return new MemorySource(qualifiedName, text);
        }
    }

    static boolean compile(Path base, List<Source> sources) throws IOException {
        Path classes = Files.createDirectories(base.resolve("classes"));
        Path generated = Files.createDirectories(base.resolve("generated"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<File> classpath = currentClasspath();
            fileManager.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            fileManager.setLocation(javax.tools.StandardLocation.SOURCE_OUTPUT, List.of(generated.toFile()));
            fileManager.setLocation(javax.tools.StandardLocation.CLASS_PATH, classpath);
            fileManager.setLocation(javax.tools.StandardLocation.ANNOTATION_PROCESSOR_PATH, classpath);

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of("-proc:full", "-processorpath",
                            String.join(File.pathSeparator, classpath.stream().map(File::getPath).toList())),
                    null,
                    sources.stream().map(Source::toFileObject).toList());
            boolean success = Boolean.TRUE.equals(task.call());
            Compilation compilation = new Compilation(classes, generated, diagnostics.getDiagnostics());
            lastCompilation.set(compilation);
            return success;
        }
    }

    static Compilation lastCompilation() {
        Compilation compilation = lastCompilation.get();
        if (compilation == null) {
            throw new AssertionError("no compilation has run");
        }
        return compilation;
    }

    static List<String> errorMessages(Compilation compilation) {
        List<String> messages = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : compilation.diagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                messages.add(diagnostic.getMessage(Locale.ROOT));
            }
        }
        return messages;
    }

    static void assertSuccess(Compilation compilation) {
        List<String> errors = errorMessages(compilation);
        assertTrue(errors.isEmpty(), () -> "unexpected compiler errors: " + errors);
    }

    private static List<File> currentClasspath() {
        LinkedHashSet<File> files = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                files.add(new File(entry));
            }
        }
        ClassLoader loader = CompilerKit.class.getClassLoader();
        for (String resource : List.of(
                "io/knotra/NoConfig.class",
                "io/knotra/beans/Beans.class",
                "io/knotra/beans/annotation/KnotraBean.class",
                "io/knotra/beans/processor/KnotraBeanProcessor.class")) {
            URL url = loader.getResource(resource);
            if (url == null) {
                throw new AssertionError("test classpath resource not found: " + resource);
            }
            files.add(classpathEntry(url, resource));
        }
        return new ArrayList<>(files);
    }

    private static File classpathEntry(URL url, String resource) {
        try {
            String external = url.toExternalForm();
            String entryUri;
            if (external.startsWith("jar:")) {
                int separator = external.indexOf("!/");
                if (separator < 0) {
                    throw new IllegalArgumentException("jar URL has no separator: " + external);
                }
                entryUri = external.substring("jar:".length(), separator);
            } else {
                entryUri = external.substring(0, external.length() - resource.length());
            }
            return new File(URI.create(entryUri));
        } catch (IllegalArgumentException error) {
            throw new AssertionError("invalid test classpath URL: " + url, error);
        }
    }

    private static final ThreadLocal<Compilation> lastCompilation = new ThreadLocal<>();

    private static final class MemorySource extends SimpleJavaFileObject {
        private final String text;

        MemorySource(String qualifiedName, String text) {
            super(URI.create("memory:///" + qualifiedName.replace('.', '/') + ".java"), Kind.SOURCE);
            this.text = text;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return text;
        }
    }

    private CompilerKit() {
    }
}
