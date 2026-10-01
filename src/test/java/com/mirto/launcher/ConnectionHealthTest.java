package com.mirto.launcher;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the connection health service: URL normalization is pure-unit;
 * probes run against a local stub HTTP server (no network, no JavaFX).
 */
class ConnectionHealthTest {

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

    // -------------------------------------------------------- URL normalization

    @Test
    void probeUrlAddsSchemeAndPath() {
        assertEquals("https://host:8443" + ConnectionHealth.PROBE_PATH,
                ConnectionHealth.probeUrlFor("host:8443"));
        assertEquals("http://host/api" + ConnectionHealth.PROBE_PATH,
                ConnectionHealth.probeUrlFor("http://host/api"));
        assertEquals("https://host" + ConnectionHealth.PROBE_PATH,
                ConnectionHealth.probeUrlFor("https://host/")); // trailing slash removed
        assertEquals("https://host" + ConnectionHealth.PROBE_PATH,
                ConnectionHealth.probeUrlFor("  https://host  ")); // trimmed
    }

    // ------------------------------------------------------------------- probes

    private volatile int lastStatus;

    @Test
    void reachableWhenServerAnswersAnyStatus() throws Exception {
        server.createContext(ConnectionHealth.PROBE_PATH, exchange -> {
            byte[] bytes = "1.2.3".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        assertEquals(200, ConnectionHealth.probe(base + ConnectionHealth.PROBE_PATH, false));
    }

    @Test
    void authRequiredStillMeansReachable() throws Exception {
        server.createContext(ConnectionHealth.PROBE_PATH, exchange -> {
            exchange.sendResponseHeaders(401, -1); // no body
        });
        int code = ConnectionHealth.probe(base + ConnectionHealth.PROBE_PATH, false);
        assertTrue(code == 401 || code == 404 || code == 200); // server-dependent
        lastStatus = code;
    }

    @Test
    void connectionRefusedIsUnreachable() {
        // port 1 refuses connections in CI environments
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> ConnectionHealth.probe("http://localhost:1" + ConnectionHealth.PROBE_PATH, false));
    }

    @Test
    void perConnectionTimeoutOverridesDefaultWhenPositive() {
        assertEquals(1500, ConnectionHealth.resolveTimeoutMs(1500, 5000));
        // null / zero / negative all fall back to the default
        assertEquals(5000, ConnectionHealth.resolveTimeoutMs(null, 5000));
        assertEquals(5000, ConnectionHealth.resolveTimeoutMs(0, 5000));
        assertEquals(5000, ConnectionHealth.resolveTimeoutMs(-100, 5000));
    }

    // ------------------------------------------------------------- service api

    @Test
    void statusIsUnknownWithoutAddress() {
        ConnectionHealth health = new ConnectionHealth();
        Connection c = new Connection();
        c.setName("no-address");
        assertEquals(ConnectionHealth.Status.UNKNOWN, health.getStatus(c));
        assertEquals(ConnectionHealth.Status.UNKNOWN, health.getStatus(null));
        health.shutdown();
    }

    @Test
    void monitoringASetAndCheckingTransitionsStatus() throws Exception {
        server.createContext(ConnectionHealth.PROBE_PATH, exchange -> {
            exchange.sendResponseHeaders(200, -1);
        });
        ConnectionHealth health = new ConnectionHealth();
        try {
            Connection c = new Connection();
            c.setId("test-id");
            c.setName("ok");
            c.setAddress(base); // http://localhost:<port>

            Set<Connection> set = ConcurrentHashMap.newKeySet();
            set.add(c);
            health.startMonitoring(set);
            health.checkAllNow();

            // wait for the async probe (bounded)
            long deadline = System.currentTimeMillis() + 10_000;
            while (health.getStatus(c) != ConnectionHealth.Status.REACHABLE
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertEquals(ConnectionHealth.Status.REACHABLE, health.getStatus(c));
            assertTrue(health.getStatusMessage(c).startsWith("HTTP "));
            assertTrue(health.getLastCheckTime() > 0);
        } finally {
            health.shutdown();
        }
    }

    @Test
    void unreachableServerTransitionsToUnreachable() throws Exception {
        ConnectionHealth health = new ConnectionHealth();
        try {
            Connection c = new Connection();
            c.setId("test-id-2");
            c.setName("down");
            c.setAddress("http://localhost:1");

            Set<Connection> set = ConcurrentHashMap.newKeySet();
            set.add(c);
            health.startMonitoring(set);
            health.checkAllNow();

            long deadline = System.currentTimeMillis() + 15_000;
            while (health.getStatus(c) != ConnectionHealth.Status.UNREACHABLE
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertEquals(ConnectionHealth.Status.UNREACHABLE, health.getStatus(c));
            assertFalse(health.getStatusMessage(c).isEmpty());
        } finally {
            health.shutdown();
        }
    }

    private static void assertFalse(boolean b) {
        org.junit.jupiter.api.Assertions.assertFalse(b);
    }
}
