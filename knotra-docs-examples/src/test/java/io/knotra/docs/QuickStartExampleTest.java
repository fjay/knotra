package io.knotra.docs;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class QuickStartExampleTest {

    @Test
    void simpleApiPublicationAndDynamicBeanBehaveAsDocumented() {
        QuickStartExample.Result result = QuickStartExample.run();

        assertEquals("v1: Hello, Knotra", result.firstValue());
        assertEquals("v2: Hello, Knotra", result.secondValue());
        assertNotNull(result.publication());
        assertFalse(result.firstReportAffectedMounts());
        assertFalse(result.secondReportAffectedMounts(),
                "a dynamic proxy consumer is not rebuilt for this change");
        assertEquals(1, result.rendererInstances(),
                "the dynamic proxy must follow the new provider without rebuilding the bean");
    }


    @Test
    void mainProducesTheDocumentedOutput() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            QuickStartExample.main(new String[0]);
        } finally {
            System.setOut(originalOut);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals("""
                v1: Hello, Knotra
                replacing provider
                v2: Hello, Knotra
                renderer instances: 1
                """, text);
    }
}
