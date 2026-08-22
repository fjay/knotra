package io.knotra.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MavenInstructionsTest {

    @Test
    void sourceInstallationComesBeforeTheFirstSnapshotDependency() throws IOException {
        String readme = Files.readString(projectRoot().resolve("README.md"), StandardCharsets.UTF_8);

        int install = readme.indexOf("mvn clean install");
        int dependency = readme.indexOf("<version>0.1.0-SNAPSHOT</version>");

        assertTrue(install >= 0, "README must explain source installation with mvn clean install");
        assertTrue(dependency >= 0, "README must contain a 0.1.0-SNAPSHOT dependency");
        assertTrue(install < dependency,
                "mvn clean install must appear before the first snapshot dependency");
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
