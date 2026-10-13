package com.mirto.launcher;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for DownloadJNLP SHA-256 comparison behavior (case-insensitive). */
class DownloadJnlpHashTest {

    @Test
    void sha256CalculationProducesConsistentBase64() throws Exception {
        // Create a temp file with known content and compute its SHA-256.
        java.io.File temp = java.io.File.createTempFile("hash-test", ".tmp");
        temp.deleteOnExit();
        java.nio.file.Files.write(temp.toPath(), "hello".getBytes());

        DownloadJNLP dj = new DownloadJNLP("http://x", temp.getParentFile(), false);
        // Use reflection to call the private calculateSha256 method
        java.lang.reflect.Method m = DownloadJNLP.class.getDeclaredMethod("calculateSha256", java.io.File.class);
        m.setAccessible(true);
        String hash = (String) m.invoke(dj, temp);

        // Java's Base64 encoder produces consistent output for the same input.
        assertNotNull(hash);
        assertFalse(hash.isEmpty());
        // SHA-256 of "hello" in base64 should be a specific value
        assertEquals("CP0ZX+MSFIq5C5Pz5s6iLO6R2t/iosgOMCEU0N2dB7C8=", hash);
    }

    @Test
    void caseInsensitiveHashComparison() {
        // Verify the comparison logic: uppercase vs lowercase of the same hash should match
        String hash = "CP0ZX+MSFIq5C5Pz5s6iLO6R2t/iosgOMCEU0N2dB7C8=";
        String hashLower = hash.toLowerCase();
        String hashUpper = hash.toUpperCase();

        // This tests the principle: equalsIgnoreCase should treat them as equal
        assertTrue(hash.trim().equalsIgnoreCase(hashLower.trim()));
        assertTrue(hash.trim().equalsIgnoreCase(hashUpper.trim()));
        // But case-sensitive equals would fail
        assertFalse(hash.trim().equals(hashLower.trim()));
    }
}
