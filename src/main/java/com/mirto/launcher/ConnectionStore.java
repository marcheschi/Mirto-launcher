package com.mirto.launcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns persistence of the connection list: loading, atomic saving with backup,
 * password encryption at rest and import/export helpers. Kept free of any JavaFX
 * dependency so it can be unit-tested independently of the UI.
 */
public class ConnectionStore {

    public static final String CONNECTIONS_FILE_NAME = "connections.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final File dataFolder;
    private final Path keyFile;

    public ConnectionStore(File dataFolder, File appDir) {
        this.dataFolder = dataFolder;
        // The data key lives next to (not inside) the data folder so that copying
        // or exporting the "data" directory does not automatically carry the key.
        this.keyFile = new File(appDir, PasswordCrypto.KEY_FILE_NAME).toPath();
    }

    public File connectionsFile() {
        return new File(dataFolder, CONNECTIONS_FILE_NAME);
    }

    /**
     * Loads all connections; passwords are decrypted to clear text in memory.
     * Legacy plain-text passwords keep working unchanged.
     */
    public List<Connection> load() throws IOException {
        File file = connectionsFile();
        if (!file.exists()) {
            return new ArrayList<>();
        }
        List<Connection> connections = MAPPER.readValue(file, new TypeReference<List<Connection>>() {});
        for (Connection conn : connections) {
            try {
                conn.setPassword(PasswordCrypto.decrypt(conn.getPassword(), keyFile));
            } catch (RuntimeException e) {
                throw new IOException("Unable to decrypt the password of connection \"" + conn.getName()
                        + "\". The credentials may have been created on a different machine."
                        + " Re-enter the password for this connection.", e);
            }
        }
        return connections;
    }

    /**
     * Atomically persists the connection list: serialized to a temp file first,
     * previous copy kept as timestamped backup, then moved over the target.
     * Passwords are encrypted before writing. The in-memory list is not modified.
     */
    public void save(List<Connection> connections) throws IOException {
        if (!dataFolder.exists() && !dataFolder.mkdirs() && !dataFolder.isDirectory()) {
            throw new IOException("Cannot create data folder: " + dataFolder.getAbsolutePath());
        }

        List<Connection> toStore = new ArrayList<>(connections.size());
        for (Connection conn : connections) {
            Connection copy = new Connection(conn);
            copy.setPassword(PasswordCrypto.encrypt(conn.getPassword(), keyFile));
            toStore.add(copy);
        }

        Path target = connectionsFile().toPath();
        Path temp = target.resolveSibling(CONNECTIONS_FILE_NAME + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), toStore);

        // Keep one timestamped backup of the previously saved state (best effort).
        if (Files.exists(target)) {
            Path backup = target.resolveSibling(
                    "connections-" + LocalDateTime.now().format(BACKUP_STAMP) + ".bak.json");
            try {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Backup failure must not block saving.
            }
        }

        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reads an exported/foreign connections file (passwords already clear text
     * unless they carry the encryption marker, which is stripped for safety).
     */
    public List<Connection> readImport(File file) throws IOException {
        byte[] content = Files.readAllBytes(file.toPath());
        List<Connection> imported = MAPPER.readValue(new String(content, StandardCharsets.UTF_8),
                new TypeReference<List<Connection>>() {});
        for (Connection conn : imported) {
            if (PasswordCrypto.isEncrypted(conn.getPassword())) {
                // Encrypted values from another machine cannot be decrypted here.
                conn.setPassword("");
            }
        }
        return imported;
    }

    /**
     * Writes an export file. When {@code withCredential} is false, usernames and
     * passwords are stripped from the copies being written.
     */
    public void writeExport(File file, List<Connection> connections, boolean withCredential) throws IOException {
        List<Connection> toWrite = new ArrayList<>(connections.size());
        for (Connection conn : connections) {
            Connection copy = new Connection(conn);
            if (withCredential) {
                copy.setPassword(PasswordCrypto.encrypt(conn.getPassword(), keyFile));
            } else {
                copy.setUsername("");
                copy.setPassword("");
            }
            toWrite.add(copy);
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, toWrite);
    }
}
