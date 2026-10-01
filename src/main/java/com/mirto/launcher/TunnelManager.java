package com.mirto.launcher;

/**
 * Owns the lifecycle of a background {@code ssh -N} tunnel process: starts it,
 * waits until the local port is actually forwarding end-to-end, and stops it on
 * demand. Pure logic (no JavaFX) so both the launch flow and the "Test" button
 * share one implementation.
 */
public final class TunnelManager {

    private volatile Process tunnelProcess; // running ssh -N process, if any

    /**
     * Starts the tunnel described by {@code tunnel} and blocks until the local
     * port accepts connections AND a probe through the tunnel succeeds (a broken
     * jump path would accept then immediately close the socket).
     */
    public void start(SshTunnel tunnel) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(tunnel.buildCommand());
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (Exception e) {
            throw new Exception("Cannot execute \"ssh\". Make sure an OpenSSH client is installed and available in the PATH. (" + e.getMessage() + ")");
        }
        // The tunnel must die together with this JVM even if no shutdown hook is
        // registered (e.g. when the app is started through the JavaFX launcher
        // without going through main()).
        final Process spawned = p;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (spawned.isAlive()) {
                        spawned.destroy();
                    }
                } catch (Throwable ignored) {
                    // best-effort cleanup during JVM shutdown
                }
            }, "ssh-tunnel-destroy"));
        } catch (IllegalStateException ignored) {
            // JVM already shutting down: nothing to register
        }
        tunnelProcess = p;

        boolean listening = false;
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive()) {
                int code = p.exitValue();
                throw new Exception("The SSH tunnel closed immediately (exit code " + code + ").\n" +
                        "Check the command, the jump host reachability and the SSH credentials/key.");
            }
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("localhost", tunnel.localPort), 500);
                listening = true;
                break;
            } catch (Exception ignored) {
                Thread.sleep(300);
            }
        }
        if (!listening) {
            stop();
            throw new Exception("Timed out waiting for the SSH tunnel: localhost:" + tunnel.localPort +
                    " is not accepting connections.\nThe remote endpoint may be unreachable through the jump host.");
        }

        // The local port accepting connections is not enough: ssh -L accepts the
        // socket even when it cannot open a channel to the target, then closes it
        // right away. Verify end-to-end with a protocol-agnostic probe: a working
        // tunnel stays open waiting for data (timeout), a broken one EOFs at once.
        boolean forwarding = false;
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("localhost", tunnel.localPort), 2000);
            s.setSoTimeout(3000);
            int b = -2;
            try {
                b = s.getInputStream().read();
            } catch (java.net.SocketTimeoutException e) {
                forwarding = true; // held open: the remote end is reachable
            }
            if (!forwarding && b != -1) {
                forwarding = true; // server spoke first (e.g. TLS alert): tunnel works
            }
        } catch (java.io.IOException ignored) {
            // connect/read failure means the tunnel is not usable
        }
        if (!forwarding) {
            stop();
            throw new Exception("The SSH tunnel is listening, but the jump host could not reach " +
                    tunnel.remoteHost + ":" + tunnel.remotePort + ".\n" +
                    "Check that the target host and port are correct and reachable from the jump host.");
        }
    }

    /**
     * Terminates the background tunnel process, if one is running. The tunnel
     * is normally kept alive for the whole BridgeLink session (the Java child
     * process inherits the listening port) and is destroyed when this launcher
     * exits thanks to the shutdown hook registered in main().
     */
    public void stop() {
        Process p = tunnelProcess;
        tunnelProcess = null;
        if (p != null && p.isAlive()) {
            p.destroy();
        }
    }
}
