package com.innovarhealthcare.launcher;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the update checker: version comparison is pure-unit; API parsing
 * runs against a stub HTTP server started locally.
 */
class UpdateCheckerTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        base = "http://localhost:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void serve(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    // ------------------------------------------------------------ comparison

    @Test
    void newerVersionsAreDetected() {
        assertTrue(UpdateChecker.isNewerVersion("1.7.0", "1.6.0"));
        assertTrue(UpdateChecker.isNewerVersion("2.0.0", "1.9.9"));
        assertTrue(UpdateChecker.isNewerVersion("1.10.0", "1.9.0")); // numeric compare
        assertTrue(UpdateChecker.isNewerVersion("1.6.1", "1.6.0"));
    }

    @Test
    void sameAndOlderVersionsAreNotNewer() {
        assertFalse(UpdateChecker.isNewerVersion("1.6.0", "1.6.0"));
        assertFalse(UpdateChecker.isNewerVersion("1.5.0", "1.6.0"));
        assertFalse(UpdateChecker.isNewerVersion("", "1.6.0"));
        assertFalse(UpdateChecker.isNewerVersion("garbage", "1.6.0"));
    }

    // -------------------------------------------------------------- API flow

    @Test
    void newerReleaseIsReported() {
        serve("/api", 200,
                "{\"tag_name\":\"v1.7.0\",\"html_url\":\"https://github.com/x/y/releases/tag/v1.7.0\"}");
        UpdateChecker checker = new UpdateChecker(base + "/api");
        UpdateChecker.Result r = checker.checkNow();
        assertTrue(r.updateAvailable);
        assertEquals("1.7.0", r.latestVersion);
        assertEquals("https://github.com/x/y/releases/tag/v1.7.0", r.releaseUrl);
        assertEquals("", r.error);
    }

    @Test
    void sameVersionIsNotReported() {
        serve("/api", 200, "{\"tag_name\":\"v" + "1.6.0" + "\",\"html_url\":\"u\"}");
        UpdateChecker checker = new UpdateChecker(base + "/api");
        assertFalse(checker.checkNow().updateAvailable);
    }

    @Test
    void httpErrorIsReportedAsError() {
        serve("/api", 500, "{}");
        UpdateChecker checker = new UpdateChecker(base + "/api");
        UpdateChecker.Result r = checker.checkNow();
        assertFalse(r.updateAvailable);
        assertFalse(r.error.isEmpty());
    }

    @Test
    void malformedJsonIsReportedAsError() {
        serve("/api", 200, "not json at all");
        UpdateChecker checker = new UpdateChecker(base + "/api");
        UpdateChecker.Result r = checker.checkNow();
        assertFalse(r.updateAvailable);
        assertFalse(r.error.isEmpty());
    }

    @Test
    void unreachableServerIsReportedAsError() {
        // port 1 is reserved and refuses connections in CI environments
        UpdateChecker checker = new UpdateChecker("http://localhost:1/api");
        UpdateChecker.Result r = checker.checkNow();
        assertFalse(r.updateAvailable);
        assertFalse(r.error.isEmpty());
    }

    @Test
    void currentVersionMatchesFallbackConstant() {
        String v = UpdateChecker.currentVersion();
        assertTrue(v.matches("\\d+\\.\\d+\\.\\d+.*"));
        // Single source of truth: FALLBACK_VERSION is kept in sync with pom.xml.
        assertEquals(BridgeLinkLauncher.FALLBACK_VERSION, v);
    }

    @Test
    void runningVersionIsNotReportedAsUpdate() {
        // Regression: the banner must not suggest an update to the version that
        // is already running (happened when the fallback constant drifted).
        serve("/api", 200, "{\"tag_name\":\"v" + BridgeLinkLauncher.FALLBACK_VERSION + "\",\"html_url\":\"u\"}");
        UpdateChecker checker = new UpdateChecker(base + "/api");
        assertFalse(checker.checkNow().updateAvailable);
    }
}
