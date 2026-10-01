package com.mirto.launcher;

import com.mirto.launcher.interfaces.Progress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Coordinates the launch sequence: optional SSH tunnel -&gt; JNLP download -&gt;
 * child JVM start. Pure logic (no JavaFX): progress and outcomes are reported
 * through a {@link Listener} on the worker thread, so the UI layer decides how
 * to marshal them onto the FX thread. This keeps the sequence unit-testable and
 * out of the application class.
 */
public final class LaunchOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(LaunchOrchestrator.class);

    /** Progress/outcome callbacks; invoked on the launch worker thread. */
    public interface Listener {
        void status(String message);
        void bar(double value);
        void launched();
        void cancelled();
        void failed(Throwable error);
    }

    /** Immutable snapshot of the form state needed to launch one connection. */
    public static final class Request {
        public final String address;
        public final String sshTunnelCommand; // may be empty
        public final File cacheFolder;
        public final boolean clearCacheJars;
        public final JavaConfig javaConfig;
        public final Credential credential;
        public final boolean showConsole;
        public final String iconPath;       // resolved absolute path, or null
        public final String connectionName; // may be null

        public Request(String address, String sshTunnelCommand, File cacheFolder,
                       boolean clearCacheJars, JavaConfig javaConfig, Credential credential,
                       boolean showConsole, String iconPath, String connectionName) {
            this.address = address;
            this.sshTunnelCommand = sshTunnelCommand;
            this.cacheFolder = cacheFolder;
            this.clearCacheJars = clearCacheJars;
            this.javaConfig = javaConfig;
            this.credential = credential;
            this.showConsole = showConsole;
            this.iconPath = iconPath;
            this.connectionName = connectionName;
        }
    }

    private final TunnelManager tunnels;
    private volatile Thread launchThread;
    private volatile DownloadJNLP currentDownload;

    public LaunchOrchestrator(TunnelManager tunnels) {
        this.tunnels = tunnels;
    }

    /** Starts the launch sequence on a dedicated thread. */
    public void start(Request req, Listener listener) {
        Thread t = new Thread(() -> run(req, listener), "Launch Thread");
        launchThread = t;
        t.start();
    }

    private void run(Request req, Listener listener) {
        try {
            String host = req.address;

            // Optional SSH tunnel: open it first, then route the download
            // through the local end of the tunnel.
            if (req.sshTunnelCommand != null && !req.sshTunnelCommand.trim().isEmpty()) {
                SshTunnel tunnel = parseTunnel(req.sshTunnelCommand);
                listener.status("Opening SSH tunnel on localhost:" + tunnel.localPort + "...");
                tunnels.start(tunnel);
                host = tunnel.rewriteUrl(host);
                LOG.debug("SSH tunnel active: {}", String.join(" ", tunnel.buildCommand()));
                listener.status("Launching " + host + " (via SSH tunnel)");
            }

            listener.status("Downloading JNLP from " + host);
            DownloadJNLP download = new DownloadJNLP(host, req.cacheFolder, req.clearCacheJars);
            currentDownload = download;
            CodeBase codeBase = download.handle(new Progress() {
                @Override
                public void updateProgressBar(double progress) {
                    listener.bar(progress);
                }

                @Override
                public void updateProgressText(String message) {
                    listener.status(message);
                }
            });
            currentDownload = null;

            listener.status("Starting application...");
            ProcessLauncher process = new ProcessLauncher();
            process.launch(req.javaConfig, req.credential, codeBase, req.showConsole, req.iconPath, req.connectionName);

            listener.status("Application launched successfully");
            Thread.sleep(1000); // let the UI show success before closing/resetting
            listener.launched();
        } catch (InterruptedException e) {
            listener.cancelled();
        } catch (Exception e) {
            // Failed to launch: do not leave an orphan tunnel behind
            tunnels.stop();
            listener.failed(e);
        } finally {
            currentDownload = null;
        }
    }

    /** @return true while a launch sequence is in flight on its worker thread. */
    public boolean isRunning() {
        Thread t = launchThread;
        return t != null && t.isAlive();
    }

    /** Cancels a running launch and cleans up any tunnel it opened. */
    public void cancel() {
        Thread t = launchThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
            DownloadJNLP d = currentDownload;
            if (d != null) {
                d.cancel();
            }

            // Give a moment for cancellation to propagate
            try {
                t.join(1000); // Wait up to 1 second for thread to exit
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupted status
            }
            tunnels.stop();
        }
    }

    /** Parses an already-validated ssh tunnel command into its components. */
    private static SshTunnel parseTunnel(String command) {
        String error = SshTunnel.validate(command);
        if (error != null) {
            throw new IllegalStateException("Invalid SSH Tunnel command: " + error);
        }
        SshTunnel tunnel = SshTunnel.parse(command);
        // Fail fast with a clear message instead of letting ssh die on a busy port.
        String portError = SshTunnel.checkLocalPortFree(tunnel.localPort);
        if (portError != null) {
            throw new IllegalStateException(portError);
        }
        return tunnel;
    }
}
