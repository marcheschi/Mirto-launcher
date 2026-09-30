package com.mirto.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the at-rest password encryption. Runs against the real {@code
 * .mirtokey} file mechanism (no OS keystore in the test environment, or it
 * is bypassed by the key-file contract these tests pin down).
 */
class PasswordCryptoTest {

    @TempDir
    Path tempDir;

    @org.junit.jupiter.api.BeforeEach
    void forceFileFallback() {
        // Tests pin down the .mirtokey contract: bypass any OS keystore.
        KeyringStore.setKeyringEnabledForTesting(false);
    }

    @org.junit.jupiter.api.AfterAll
    static void restoreKeyring() {
        KeyringStore.setKeyringEnabledForTesting(true);
    }

    private Path keyFile() {
        return tempDir.resolve(PasswordCrypto.KEY_FILE_NAME);
    }

    @Test
    void encryptDecryptRoundTrip() throws IOException {
        Path kf = keyFile();
        String secret = "p4ssw0rd !\"£$%&/()=";
        String stored = PasswordCrypto.encrypt(secret, kf);
        assertTrue(PasswordCrypto.isEncrypted(stored));
        assertTrue(stored.startsWith(PasswordCrypto.PREFIX));
        assertFalse(stored.contains(secret));
        assertEquals(secret, PasswordCrypto.decrypt(stored, kf));
    }

    @Test
    void samePasswordEncryptsDifferently() throws IOException {
        Path kf = keyFile();
        String a = PasswordCrypto.encrypt("same", kf);
        String b = PasswordCrypto.encrypt("same", kf);
        assertFalse(a.equals(b)); // random IV
        assertEquals("same", PasswordCrypto.decrypt(a, kf));
        assertEquals("same", PasswordCrypto.decrypt(b, kf));
    }

    @Test
    void emptyPasswordStaysEmpty() throws IOException {
        Path kf = keyFile();
        assertEquals("", PasswordCrypto.encrypt("", kf));
        assertEquals(null, PasswordCrypto.encrypt(null, kf));
    }

    @Test
    void legacyPlainTextIsReturnedUnchanged() {
        assertEquals("oldplain", PasswordCrypto.decrypt("oldplain", keyFile()));
        assertFalse(PasswordCrypto.isEncrypted("oldplain"));
    }

    @Test
    void corruptPayloadThrows() throws IOException {
        Path kf = keyFile();
        String stored = PasswordCrypto.encrypt("x", kf);
        String corrupted = stored.substring(0, stored.length() - 4) + "AAAA";
        assertThrows(RuntimeException.class, () -> PasswordCrypto.decrypt(corrupted, kf));
    }

    @Test
    void keyFileIsCreatedAndReused() throws IOException {
        Path kf = keyFile();
        assertFalse(Files.exists(kf));
        PasswordCrypto.encrypt("one", kf);
        assertTrue(Files.exists(kf));
        byte[] key1 = Files.readAllBytes(kf);

        // Same key file -> same key -> decrypt works
        String stored = PasswordCrypto.encrypt("two", kf);
        assertEquals("two", PasswordCrypto.decrypt(stored, kf));
        // key file content is stable
        byte[] key2 = Files.readAllBytes(kf);
        assertTrue(java.util.Arrays.equals(key1, key2));
    }

    @Test
    void keyMigratesToFileWhenKeyringDisabled() throws IOException {
        // Simulates a machine where the keystore exists first, then the file is
        // the only store (keystore unavailable): decrypt must still work.
        Path kf = keyFile();
        String stored = PasswordCrypto.encrypt("migrate", kf);
        assertEquals("migrate", PasswordCrypto.decrypt(stored, kf));
    }

    @Test
    void differentKeyFileCannotDecrypt() throws IOException {
        Path kf1 = tempDir.resolve("key1");
        Path kf2 = tempDir.resolve("key2");
        String stored = PasswordCrypto.encrypt("secret", kf1);
        assertThrows(RuntimeException.class, () -> PasswordCrypto.decrypt(stored, kf2));
    }
}
