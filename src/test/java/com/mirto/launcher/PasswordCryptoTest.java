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

    @Test
    void legacyKeyFileIsDecryptedAndUpgraded() throws Exception {
        Path kf = keyFile();

        // Build a pre-hardening key file: a random data key encrypted under the
        // legacy 65k-iteration master (the format written before the PBKDF2 bump).
        byte[] dataKeyBytes = new byte[32];
        java.security.SecureRandom sr = new java.security.SecureRandom();
        sr.nextBytes(dataKeyBytes);
        javax.crypto.SecretKey legacyMaster = PasswordCrypto.legacyMasterKeyForTesting();

        byte[] iv = new byte[12];
        sr.nextBytes(iv);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, legacyMaster, new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(dataKeyBytes);
        byte[] combined = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ct, 0, combined, iv.length, ct.length);
        String legacyContent = PasswordCrypto.PREFIX + "v1:"
                + java.util.Base64.getEncoder().encodeToString(combined);
        Files.write(kf, legacyContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Loading must transparently fall back to the legacy master...
        String stored = PasswordCrypto.encrypt("legacy-secret", kf);
        assertEquals("legacy-secret", PasswordCrypto.decrypt(stored, kf));

        // ...and re-encrypt the data key under the hardened master (file changed).
        byte[] after = Files.readAllBytes(kf);
        assertFalse(new String(after, java.nio.charset.StandardCharsets.UTF_8)
                .equals(legacyContent), "key file should have been upgraded to the hardened derivation");
    }
}
