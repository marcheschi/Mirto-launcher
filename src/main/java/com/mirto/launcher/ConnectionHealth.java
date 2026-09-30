package com.mirto.launcher;

import javafx.application.Platform;
import org.apache.commons.lang3.StringUtils;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically probes the reachability of every connection's server and keeps
 * the result in an in-memory cache for the UI (green/red dot next to each
 * connection in the tree).
 *
 * <p>The probe is a plain HTTPS GET to {@code <address>/api/server/version} —
 * the Mirth Connect public version endpoint: it does not require credentials
 * and returns quickly. A connection is REACHABLE when the server answers with
 * any HTTP response (even 401/403/404: what matters is that the endpoint
 * answers), UNREACHABLE on connection failures or timeouts.
 *
 * <p>Connections flagged with "Trust self-signed certificate" are probed with
 * a per-connection trust-all SSL factory (the bypass is NOT applied globally,
 * so regular connections are still verified normally).
 *
 * <p>Results are keyed by connection identity (id when present, else
 * name+address) so statuses survive tree rebuilds (filtering, grouping).
 * Java-8 compatible: a daemon scheduler triggers rounds, a small worker pool
 * performs the probes so one slow endpoint never delays the others.
 */
public class ConnectionHealth {

    /** Possible reachability states shown in the UI. */
    public enum Status {
        /** Not probed yet (or connection has no address). */
        UNKNOWN,
        /** Probe currently in flight. */
        CHECKING,
        /** Server answered (any HTTP status counts). */
        REACHABLE,
        /** Connection failed or timed out. */
        UNREACHABLE
    }

    /** Callback invoked on the JavaFX Application Thread after each probe. */
    public interface Listener {
        void onStatusChanged(Connection connection, Status status, String message);
    }

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final long PERIOD_MINUTES = 5;
    private static final int WORKER_THREADS = 3;
    /** Mirth Connect public endpoint: no auth required, small response. */
    static final String PROBE_PATH = "/api/server/version";

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Set<Connection> monitored = new CopyOnWriteArraySet<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "connection-health-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKER_THREADS, r -> {
        Thread t = new Thread(r, "connection-health-probe");
        t.setDaemon(true);
        return t;
    });

    private volatile Listener listener;
    private volatile long lastCheckTime = 0L;
    /** Test hook: set false to keep lastCheckTime frozen. */
    volatile boolean updateTimestamps = true;

    private static final class Entry {
        volatile Status status = Status.UNKNOWN;
        volatile String message = "";
    }

    /** Registers the callback invoked (on the FX thread) after every probe. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Starts monitoring the given connection set: an immediate round followed by
     * one round every {@link #PERIOD_MINUTES} minutes. Later additions to the
     * set are probed on the next round; call {@link #check(Connection)} to probe
     * them immediately.
     */
    public void startMonitoring(Set<Connection> connections) {
        monitored.addAll(connections);
        scheduler.scheduleWithFixedDelay(this::checkAllNow, 0, PERIOD_MINUTES, TimeUnit.MINUTES);
    }

    /** Triggers an immediate probe of all monitored connections. */
    public void checkAllNow() {
        for (Connection c : monitored) {
            submit(c);
        }
    }

    /** Probes a single connection (async). */
    public void check(Connection c) {
        if (c == null || StringUtils.isBlank(c.getAddress())) {
            return;
        }
        monitored.add(c);
        submit(c);
    }

    /** @return the cached status for the given connection. */
    public Status getStatus(Connection connection) {
        if (connection == null || StringUtils.isBlank(connection.getAddress())) {
            return Status.UNKNOWN;
        }
        Entry e = cache.get(identityOf(connection));
        return e != null ? e.status : Status.UNKNOWN;
    }

    /** @return the last error/diagnostic message for the given connection (may be empty). */
    public String getStatusMessage(Connection connection) {
        if (connection == null) {
            return "";
        }
        Entry e = cache.get(identityOf(connection));
        return e != null ? e.message : "";
    }

    /** @return epoch millis of the last completed check round (0 = never). */
    public long getLastCheckTime() {
        return lastCheckTime;
    }

    /** Stops the background probes. Called when the application window closes. */
    public void shutdown() {
        scheduler.shutdownNow();
        workers.shutdownNow();
    }

    // ------------------------------------------------------------------ core

    private void submit(final Connection c) {
        workers.submit(() -> runProbe(c));
    }

    private void runProbe(Connection c) {
        String address = c.getAddress() == null ? "" : c.getAddress().trim();
        if (address.isEmpty()) {
            update(c, Status.UNKNOWN, "");
            return;
        }
        String probeUrl = probeUrlFor(address);

        Entry entry = cache.computeIfAbsent(identityOf(c), k -> new Entry());
        entry.status = Status.CHECKING;
        entry.message = "";
        fire(c, entry);

        try {
            int code = probe(probeUrl, c.isTrustSelfSignedCertificate());
            // Any HTTP answer means the endpoint is alive: 200 OK, but also
            // 401 (auth required) or 404 (endpoint moved) prove reachability.
            entry.status = Status.REACHABLE;
            entry.message = "HTTP " + code;
        } catch (Exception e) {
            entry.status = Status.UNREACHABLE;
            String msg = e.getMessage();
            entry.message = msg != null && !msg.isEmpty() ? msg : e.getClass().getSimpleName();
        }
        if (updateTimestamps) {
            lastCheckTime = System.currentTimeMillis();
        }
        fire(c, entry);
    }

    private void update(Connection c, Status status, String message) {
        Entry entry = cache.computeIfAbsent(identityOf(c), k -> new Entry());
        entry.status = status;
        entry.message = message;
        fire(c, entry);
    }

    private void fire(final Connection c, final Entry entry) {
        Listener l = listener;
        if (l != null) {
            final Status st = entry.status;
            final String msg = entry.message;
            Platform.runLater(() -> l.onStatusChanged(c, st, msg));
        }
    }

    /**
     * Builds the probe URL: normalizes the scheme (https when missing) and
     * appends the Mirth public version endpoint.
     */
    static String probeUrlFor(String address) {
        String a = address.trim();
        if (!a.toLowerCase().matches("^https?://.*")) {
            a = "https://" + a;
        }
        if (a.endsWith("/")) {
            a = a.substring(0, a.length() - 1);
        }
        return a + PROBE_PATH;
    }

    /** Performs the HTTPS/HTTP GET, returning the HTTP status code. */
    static int probe(String probeUrl, boolean trustSelfSigned) throws IOException {
        HttpURLConnection conn;
        URL url = new URL(probeUrl);
        if ("https".equalsIgnoreCase(url.getProtocol())) {
            HttpsURLConnection https = (HttpsURLConnection) url.openConnection();
            if (trustSelfSigned) {
                https.setSSLSocketFactory(trustAllFactory().getSocketFactory());
                https.setHostnameVerifier(TRUST_ALL_HOSTS);
            }
            conn = https;
        } else {
            conn = (HttpURLConnection) url.openConnection();
        }
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        try {
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    private static final HostnameVerifier TRUST_ALL_HOSTS = (hostname, session) -> true;

    private static volatile SSLContext trustAllContext;

    /** Lazily created trust-all SSL context, shared by all self-signed probes. */
    private static SSLContext trustAllFactory() throws IOException {
        SSLContext ctx = trustAllContext;
        if (ctx != null) {
            return ctx;
        }
        synchronized (ConnectionHealth.class) {
            if (trustAllContext == null) {
                try {
                    TrustManager[] trustAll = new TrustManager[]{
                            new X509TrustManager() {
                                public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                                public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                            }
                    };
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, trustAll, new SecureRandom());
                    trustAllContext = sc;
                } catch (Exception e) {
                    throw new IOException("Cannot initialize trust-all SSL context: " + e.getMessage(), e);
                }
            }
            return trustAllContext;
        }
    }

    /**
     * Stable identity for caching: connection id when present (survives renames
     * and tree rebuilds), otherwise name+address.
     */
    private static String identityOf(Connection c) {
        String id = c.getId();
        if (id != null && !id.trim().isEmpty()) {
            return "id:" + id;
        }
        return "name:" + c.getName() + "|addr:" + c.getAddress();
    }
}
