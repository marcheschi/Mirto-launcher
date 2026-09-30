package com.mirto.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Checks GitHub for a newer release of the launcher and reports it to the UI.
 *
 * <p>Queries the public GitHub Releases API of this repository and compares the
 * latest published release tag with the running version. The network call is
 * never performed on the JavaFX thread: callers use {@link #checkAsync} and get
 * the result back through the callback, already marshalled onto the FX thread.
 *
 * <p>All failures are swallowed and reported as "up to date" result with an
 * error message: the update check must never disturb normal usage.
 */
public class UpdateChecker {

    /** Owner/repo of the GitHub project whose releases are checked. */
    static final String REPO = "marcheschi/Mirto-launcher";
    /** Timeout for the API call (ms). */
    private static final int TIMEOUT_MS = 5000;
    /** Minimum delay between two checks (ms): avoids hammering the API. */
    private static final long MIN_CHECK_INTERVAL_MS = 60 * 60 * 1000L; // 1 hour

    private final String apiUrl;

    /** Timestamp of the last completed check (0 = never). */
    private volatile long lastCheckTime = 0L;

    public UpdateChecker() {
        this("https://api.github.com/repos/" + REPO + "/releases/latest");
    }

    /** Visible for testing: allows pointing the checker at a stub server. */
    UpdateChecker(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    /** Result of an update check, delivered on the JavaFX Application Thread. */
    public static final class Result {
        /** True when the latest published release is newer than the running one. */
        public final boolean updateAvailable;
        /** Latest release tag, e.g. "v1.7.0" (empty when the check failed). */
        public final String latestVersion;
        /** Direct URL of the release page (for the "open" action in the UI). */
        public final String releaseUrl;
        /** Non-empty when the check could not be completed. */
        public final String error;

        Result(boolean updateAvailable, String latestVersion, String releaseUrl, String error) {
            this.updateAvailable = updateAvailable;
            this.latestVersion = latestVersion;
            this.releaseUrl = releaseUrl;
            this.error = error;
        }
    }

    /** Callback invoked with the check result on the JavaFX Application Thread. */
    public interface Callback {
        void onUpdateCheckResult(Result result);
    }

    /**
     * Asynchronously checks GitHub for a newer release. Results are delivered
     * exactly once, on the FX thread. Skips silently when the previous check is
     * more recent than one hour, unless {@code force} is true.
     */
    public void checkAsync(boolean force, final Callback callback) {
        long now = System.currentTimeMillis();
        if (!force && lastCheckTime != 0 && (now - lastCheckTime) < MIN_CHECK_INTERVAL_MS) {
            return;
        }
        Thread t = new Thread(() -> {
            Result result = checkNow();
            lastCheckTime = System.currentTimeMillis();
            if (callback != null) {
                javafx.application.Platform.runLater(() -> callback.onUpdateCheckResult(result));
            }
        }, "update-checker");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Synchronous check. Package-private for tests; UI code uses
     * {@link #checkAsync(boolean, Callback)}.
     */
    Result checkNow() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "Mirto-Launcher");
            try {
                if (conn.getResponseCode() != 200) {
                    return new Result(false, "", "", "HTTP " + conn.getResponseCode());
                }
                String body = readAll(conn.getInputStream());
                JsonNode json = new ObjectMapper().readTree(body);
                String tag = json.path("tag_name").asText("");
                String htmlUrl = json.path("html_url").asText("");
                if (tag.isEmpty()) {
                    return new Result(false, "", htmlUrl, "Missing tag_name in response");
                }
                String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                boolean newer = isNewerVersion(latest, currentVersion());
                return new Result(newer, latest, htmlUrl, "");
            } finally {
                conn.disconnect();
            }
        } catch (IOException e) {
            return new Result(false, "", "", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** @return the running application version (jar manifest or fallback constant). */
    static String currentVersion() {
        try {
            String v = BridgeLinkLauncher.class.getPackage().getImplementationVersion();
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        } catch (Exception ignored) {
            // fall through to the constant
        }
        // Single source of truth: must stay in sync with pom.xml via FALLBACK_VERSION.
        return BridgeLinkLauncher.FALLBACK_VERSION;
    }

    /**
     * Semantic-ish version comparison: compares dot-separated numeric parts
     * left to right ("1.10.0" > "1.9.2"); non-numeric suffixes are ignored.
     */
    static boolean isNewerVersion(String candidate, String current) {
        int[] c = parseVersion(candidate);
        int[] cur = parseVersion(current);
        for (int i = 0; i < Math.max(c.length, cur.length); i++) {
            int a = i < c.length ? c[i] : 0;
            int b = i < cur.length ? cur[i] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    private static int[] parseVersion(String v) {
        if (v == null) {
            return new int[]{0};
        }
        String[] parts = v.trim().split("[.\\-]");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            StringBuilder digits = new StringBuilder();
            for (char ch : p.toCharArray()) {
                if (Character.isDigit(ch)) {
                    digits.append(ch);
                } else {
                    break; // stop at first non-digit ("0-rc1" -> 0)
                }
            }
            out[i] = digits.length() > 0 ? Integer.parseInt(digits.toString()) : 0;
        }
        return out;
    }
}
