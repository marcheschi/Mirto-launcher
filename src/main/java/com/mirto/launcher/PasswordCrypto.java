package com.mirto.launcher;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Encrypts/decrypts connection passwords at rest so that {@code connections.json}
 * never stores them in plain text.
 *
 * <p>Design notes:
 * <ul>
 *   <li>A random 256-bit data key is generated on first use. When an OS keystore
 *       is available (Windows Credential Manager, macOS Keychain, Linux
 *       GNOME/KDE keyring) the key is stored there, bound to the user account.
 *       Otherwise it is stored next to the data folder, AES-GCM encrypted with a
 *       machine-derived key, in a hidden file readable only by the current
 *       user.</li>
 *   <li>The machine-derived fallback key combines the user name, OS name and
 *       the JVM installation path (PBKDF2-SHA256, 210k iterations), so the data
 *       key cannot be decrypted from a different machine/user out of the box.
 *       Key files written with the previous 65k-iteration derivation keep
 *       decrypting and are transparently re-encrypted on first use.</li>
 *   <li>Passwords are stored base64-encoded as {@code iv:ciphertext}. Any value
 *       that does not carry the {@link #PREFIX} marker is treated as legacy
 *       plain text: it still works (backward compatibility) but gets re-encrypted
 *       transparently the next time the connection is saved.</li>
 * </ul>
 *
 * <p>This protects against casual exposure (screen sharing, backups, someone
 * opening the JSON file) but is not a full secret-management solution: an attacker
 * with the same user account on the same machine can reproduce the derived key.
 */
public final class PasswordCrypto {

    /** Marker identifying an encrypted value. */
    public static final String PREFIX = "ENC1:";

    /** Prefix of the payload following {@link #PREFIX} for real AES-GCM data. */
    private static final String ENCRYPTED_PREFIX = "v1:";

    /** Name of the hidden data-key file, stored inside the application folder. */
    public static final String KEY_FILE_NAME = ".mirtokey";

    /** Pre-rename key file name: migrated to {@link #KEY_FILE_NAME} on first use. */
    private static final String LEGACY_KEY_FILE_NAME = ".bridgekey";
    private static final int KEY_SIZE_BITS = 256;
    private static final int IV_SIZE_BYTES = 12;      // recommended for AES-GCM
    private static final int TAG_SIZE_BITS = 128;
    /** Iterations for newly written key files (OWASP 2023 minimum for PBKDF2-SHA256). */
    private static final int PBKDF2_ITERATIONS = 210_000;
    /** Iterations used before the hardening: legacy key files must keep decrypting. */
    private static final int LEGACY_PBKDF2_ITERATIONS = 65_536;

    private static final SecureRandom RANDOM = new SecureRandom();

    // The master keys are deterministic per machine/user, so derive them once and
    // cache: loadOrCreateKey() runs on every password operation.
    private static volatile SecretKey cachedCurrentMaster;
    private static volatile SecretKey cachedLegacyMaster;

    private PasswordCrypto() {
    }

    /**
     * @return true if the given stored value is one produced by this class.
     */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * Returns the clear-text password for a stored value. Plain values (legacy
     * data written before encryption was introduced) are returned unchanged.
     */
    public static String decrypt(String stored, Path keyFile) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        String payload = stored.substring(PREFIX.length());
        if (!payload.startsWith(ENCRYPTED_PREFIX)) {
            throw new IllegalStateException("Unsupported encrypted-password format");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(payload.substring(ENCRYPTED_PREFIX.length()));
            if (combined.length <= IV_SIZE_BYTES) {
                throw new IOException("Invalid encrypted payload length");
            }
            byte[] iv = new byte[IV_SIZE_BYTES];
            byte[] cipherText = new byte[combined.length - IV_SIZE_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_SIZE_BYTES);
            System.arraycopy(combined, IV_SIZE_BYTES, cipherText, 0, cipherText.length);

            byte[] plain = gcmDecrypt(loadOrCreateKey(keyFile), iv, cipherText);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to decrypt stored password: " + e.getMessage(), e);
        }
    }

    /**
     * Encrypts a clear-text password into a storable value. Empty/null input is
     * returned unchanged (nothing to protect).
     */
    public static String encrypt(String plain, Path keyFile) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        if (isEncrypted(plain)) {
            return plain; // already in encrypted form
        }
        try {
            byte[] combined = gcmEncrypt(loadOrCreateKey(keyFile), plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Unable to encrypt password: " + e.getMessage(), e);
        }
    }

    /**
     * Loads the persisted data key, creating and storing one on first use.
     *
     * <p>Storage strategy: the OS keystore is preferred when available; the
     * encrypted {@code .bridgekey} file is the fallback. When a key already
     * exists in one store and the other is used later, the key is migrated so
     * existing passwords keep working after an upgrade (and vice versa when
     * the keystore becomes unavailable).
     */
    private static synchronized SecretKey loadOrCreateKey(Path keyFile) throws IOException, GeneralSecurityException {
        SecretKey master = deriveMasterKey();

        // 0. Compatibility: migrate the pre-rename key file (.bridgekey) in the same folder.
        Path parent = keyFile.getParent();
        if (parent != null && !Files.exists(keyFile)) {
            Path legacy = parent.resolve(LEGACY_KEY_FILE_NAME);
            if (Files.exists(legacy)) {
                try {
                    Files.move(legacy, keyFile);
                } catch (IOException ignored) {
                    // Fall through: the legacy file will simply be re-read below.
                }
            }
        }

        // 1. OS keystore (preferred)
        if (KeyringStore.isAvailable()) {
            Optional<byte[]> stored = KeyringStore.load();
            if (stored.isPresent()) {
                return new SecretKeySpec(stored.get(), "AES");
            }
        }

        // 2. Encrypted file fallback (and migration source from pre-keyring installs)
        if (Files.exists(keyFile)) {
            try {
                String stored = new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8).trim();
                String payload = stored.substring(PREFIX.length());
                byte[] combined = Base64.getDecoder().decode(payload.substring(ENCRYPTED_PREFIX.length()));
                if (combined.length <= IV_SIZE_BYTES) {
                    throw new IOException("Invalid data-key payload length in " + keyFile);
                }
                byte[] iv = new byte[IV_SIZE_BYTES];
                byte[] cipherText = new byte[combined.length - IV_SIZE_BYTES];
                System.arraycopy(combined, 0, iv, 0, IV_SIZE_BYTES);
                System.arraycopy(combined, IV_SIZE_BYTES, cipherText, 0, cipherText.length);

                // Try the hardened master first; key files written before the PBKDF2
                // hardening only decrypt with the legacy iteration count. A wrong key
                // fails cleanly via the GCM authentication tag (AEADBadTagException).
                byte[] keyBytes;
                boolean wasLegacy = false;
                try {
                    keyBytes = gcmDecrypt(master, iv, cipherText);
                } catch (AEADBadTagException e) {
                    keyBytes = gcmDecrypt(deriveMasterKey(LEGACY_PBKDF2_ITERATIONS), iv, cipherText);
                    wasLegacy = true;
                }
                SecretKey dataKey = new SecretKeySpec(keyBytes, 0, KEY_SIZE_BITS / 8, "AES");

                // One-time upgrade: re-encrypt the data key under the hardened master.
                if (wasLegacy) {
                    try {
                        Files.write(keyFile, (PREFIX + ENCRYPTED_PREFIX
                                + Base64.getEncoder().encodeToString(gcmEncrypt(master, keyBytes)))
                                .getBytes(StandardCharsets.UTF_8));
                        restrictPermissions(keyFile);
                    } catch (IOException | GeneralSecurityException ignored) {
                        // Non-fatal: the legacy file keeps working until the next write.
                    }
                }

                // Migrate to the OS keystore when available (one-way upgrade).
                if (KeyringStore.isAvailable() && KeyringStore.save(keyBytes)) {
                    Files.deleteIfExists(keyFile);
                }
                return dataKey;
            } catch (GeneralSecurityException | IllegalArgumentException | IndexOutOfBoundsException e) {
                throw new IOException("Data key file is corrupt or was created on another machine: " + keyFile, e);
            }
        }

        // 3. Generate a fresh random data key and persist it.
        byte[] keyBytes = new byte[KEY_SIZE_BITS / 8];
        RANDOM.nextBytes(keyBytes);
        SecretKey dataKey = new SecretKeySpec(keyBytes, "AES");

        boolean savedToKeyring = false;
        if (KeyringStore.isAvailable()) {
            savedToKeyring = KeyringStore.save(keyBytes);
        }
        if (!savedToKeyring) {
            byte[] combined = gcmEncrypt(master, keyBytes);
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(keyFile, (PREFIX + ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(combined))
                    .getBytes(StandardCharsets.UTF_8));
            restrictPermissions(keyFile);
        }
        return dataKey;
    }

    /**
     * Derives a machine-bound key (PBKDF2) from non-secret environment facts.
     * Cached per iteration count: the inputs never change within a JVM run and
     * loadOrCreateKey() is invoked on every password operation.
     */
    private static synchronized SecretKey deriveMasterKey(int iterations) throws GeneralSecurityException {
        SecretKey cached = (iterations == LEGACY_PBKDF2_ITERATIONS) ? cachedLegacyMaster : cachedCurrentMaster;
        if (cached != null) {
            return cached;
        }
        String material = System.getProperty("user.name", "") + "|"
                + System.getProperty("os.name", "") + "|"
                + System.getProperty("java.home", "");
        PBEKeySpec spec = new PBEKeySpec(material.toCharArray(),
                "BridgeLinkLauncher/salt/v1".getBytes(StandardCharsets.UTF_8),
                iterations, KEY_SIZE_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            SecretKey key = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
            if (iterations == LEGACY_PBKDF2_ITERATIONS) {
                cachedLegacyMaster = key;
            } else {
                cachedCurrentMaster = key;
            }
            return key;
        } finally {
            spec.clearPassword();
        }
    }

    /** Convenience overload: the current (hardened) master key. */
    private static SecretKey deriveMasterKey() throws GeneralSecurityException {
        return deriveMasterKey(PBKDF2_ITERATIONS);
    }

    /** Visible for testing: derives the legacy (pre-hardening) master key. */
    static SecretKey legacyMasterKeyForTesting() throws GeneralSecurityException {
        return deriveMasterKey(LEGACY_PBKDF2_ITERATIONS);
    }

    /** AES-GCM decrypt; a wrong key fails cleanly via the authentication tag. */
    private static byte[] gcmDecrypt(SecretKey key, byte[] iv, byte[] cipherText) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_SIZE_BITS, iv));
        return cipher.doFinal(cipherText);
    }

    /** AES-GCM encrypt with a fresh random IV; returns {@code iv || ciphertext}. */
    private static byte[] gcmEncrypt(SecretKey key, byte[] plain) throws GeneralSecurityException {
        byte[] iv = new byte[IV_SIZE_BYTES];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_SIZE_BITS, iv));
        byte[] cipherText = cipher.doFinal(plain);
        byte[] combined = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
        return combined;
    }

    /**
     * Best effort: make the key file owner-read/write only (POSIX), silently
     * ignoring platforms without POSIX permission support.
     */
    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) {
            // Windows or unsupported filesystem - nothing else we can portably do.
        }
    }
}
