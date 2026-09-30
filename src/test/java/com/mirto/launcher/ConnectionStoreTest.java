package com.mirto.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the connection persistence layer. Uses only the file-based key
 * fallback: the {@code .mirtokey} contract is pinned by PasswordCryptoTest.
 */
class ConnectionStoreTest {

    @TempDir
    Path tempDir;

    private ConnectionStore store() {
        File data = tempDir.resolve("data").toFile();
        File app = tempDir.toFile();
        return new ConnectionStore(data, app);
    }

    private Connection sample(String name, String password) {
        Connection c = new Connection();
        c.setId(java.util.UUID.randomUUID().toString());
        c.setName(name);
        c.setAddress("https://mirth.example.org:8443");
        c.setUsername("admin");
        c.setPassword(password);
        return c;
    }

    @Test
    void saveLoadRoundTripWithEncryption() throws IOException {
        ConnectionStore store = store();
        List<Connection> out = Arrays.asList(
                sample("Prod", "supersecret"),
                sample("Dev", "othersecret"));
        store.save(out);

        List<Connection> in = store.load();
        assertEquals(2, in.size());
        assertEquals("Prod", in.get(0).getName());
        assertEquals("supersecret", in.get(0).getPassword());
        assertEquals("othersecret", in.get(1).getPassword());
    }

    @Test
    void passwordsAreNotStoredInClearText() throws IOException {
        ConnectionStore store = store();
        store.save(Arrays.asList(sample("Prod", "plaintext-secret")));

        String fileContent = new String(Files.readAllBytes(store.connectionsFile().toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(fileContent.contains("plaintext-secret"));
        assertTrue(PasswordCrypto.isEncrypted(extractPassword(fileContent)));
    }

    /** Extracts the "password" field from the JSON for assertions. */
    private static String extractPassword(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"password\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        assertTrue(m.find());
        return m.group(1);
    }

    @Test
    void saveCreatesTimestampedBackup() throws IOException {
        ConnectionStore store = store();
        store.save(Arrays.asList(sample("A", "x")));
        store.save(Arrays.asList(sample("B", "y"))); // second save backs up the first state

        try (java.util.stream.Stream<Path> list = Files.list(tempDir.resolve("data"))) {
            long backups = list.filter(p -> p.getFileName().toString().startsWith("connections-")
                            && p.getFileName().toString().endsWith(".bak.json"))
                    .count();
            assertTrue(backups >= 1);
        }
    }

    @Test
    void exportWithoutCredentialsStripsSecrets() throws IOException {
        ConnectionStore store = store();
        List<Connection> conns = Arrays.asList(sample("Prod", "secret-value"));

        File export = tempDir.resolve("export.json").toFile();
        store.writeExport(export, conns, false);

        String content = new String(Files.readAllBytes(export.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(content.contains("secret-value"));
        assertFalse(content.contains("admin"));

        List<Connection> reimported = store.readImport(export);
        assertEquals("", reimported.get(0).getPassword());
        assertEquals("", reimported.get(0).getUsername());
    }

    @Test
    void importStripsForeignEncryptedPasswords() throws IOException {
        // Simulate an export produced on another machine: encrypted values with
        // the ENC1: marker that this installation cannot decrypt.
        String foreign = "[{\"name\":\"Other\",\"address\":\"https://x:8443\",\"password\":\""
                + PasswordCrypto.PREFIX + "v1:notbase64!!\"}]";
        File file = tempDir.resolve("foreign.json").toFile();
        Files.write(file.toPath(), foreign.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<Connection> imported = store().readImport(file);
        assertEquals("Other", imported.get(0).getName());
        assertEquals("", imported.get(0).getPassword()); // marker stripped for safety
    }

    @Test
    void loadOnMissingFileReturnsEmptyList() throws IOException {
        assertTrue(store().load().isEmpty());
    }
}
