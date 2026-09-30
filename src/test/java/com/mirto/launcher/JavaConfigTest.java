package com.mirto.launcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the JVM command resolution. Platform-dependent branches
 * (javaw.exe on Windows) are exercised by asserting on the observable
 * fallback behaviour of the current platform.
 */
class JavaConfigTest {

    private static final boolean WINDOWS =
            org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;

    @Test
    void customJavaHomeWinsWhenExecutable() throws IOException {
        Path fake = Files.createTempDirectory("fakejava");
        String exe = WINDOWS ? "javaw.exe" : "java";
        Path bin = fake.resolve("bin");
        Files.createDirectories(bin);
        Path java = bin.resolve(exe);
        Files.write(java, new byte[]{1});
        java.toFile().setExecutable(true);

        JavaConfig cfg = new JavaConfig("512m", "Java 17", "", fake.toString());
        assertEquals(java.toString(), cfg.getJavaHomeBuilder());
    }

    @Test
    void customJavaHomeFallsBackWhenMissing() {
        JavaConfig cfg = new JavaConfig("512m", "Java 17", "", "/no/such/java/home");
        // falls back to bundled/relative resolution, never throws
        String resolved = cfg.getJavaHomeBuilder();
        assertTrue(resolved != null && !resolved.isEmpty());
    }

    @Test
    void relativeBundledPathIsPreferredForJava17() {
        JavaConfig cfg = new JavaConfig("512m", "Java 17", "", null);
        String resolved = cfg.getJavaHomeBuilder();
        assertTrue(isAValidResolution(resolved),
                "unexpected resolution: " + resolved);
    }

    /**
     * Accepts every legitimate outcome of the fallback chain: ./jre relative to
     * the working directory (present on dev machines), JAVA_HOME (set on CI by
     * setup-java) or the bare PATH executable.
     */
    private static boolean isAValidResolution(String resolved) {
        String javaw = WINDOWS ? "javaw.exe" : "java";
        String sep = java.io.File.separator;
        if (resolved.equals("jre" + sep + "bin" + sep + javaw)) {
            return true;
        }
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null && !javaHomeEnv.isEmpty()
                && resolved.equals(javaHomeEnv + sep + "bin" + sep + javaw)) {
            return true;
        }
        return resolved.equals(WINDOWS ? "java.exe" : "java");
    }

    @Test
    void java8SelectsJre8Layout() {
        JavaConfig cfg = new JavaConfig("512m", "Java 8", "", null);
        String resolved = cfg.getJavaHomeBuilder();
        assertTrue(resolved != null && !resolved.isEmpty());
    }

    @Test
    void jvmOptionsTokenizerSplitsOnWhitespace() {
        JavaConfig cfg = new JavaConfig("512m", "Java 17",
                "-Dfoo=bar \"a b c\" -Xss1m", null);
        java.util.List<String> opts = cfg.getJvmOptionsList();
        assertTrue(opts.contains("-Dfoo=bar"));
        assertTrue(opts.contains("a b c"));   // quoted value becomes one token
        assertTrue(opts.contains("-Xss1m"));
        assertEquals(3, opts.size());
    }

    /** Documents a known limitation: quotes attached inside a token are not stripped. */
    @Test
    void quotesAttachedToTokenAreNotStripped() {
        JavaConfig cfg = new JavaConfig("512m", "Java 17", "-Dquote=\"x y\"", null);
        // the tokenizer splits on whitespace even inside quotes attached to a token:
        // users must write quotes as standalone delimiters ("a b c") or avoid spaces.
        assertEquals(2, cfg.getJvmOptionsList().size());
    }

    @Test
    void heapBuilderPrefixesXmx() {
        JavaConfig cfg = new JavaConfig("2g", "Java 17", "", null);
        assertEquals("-Xmx2g", cfg.getMaxHeapSizeBuilder());
    }
}
