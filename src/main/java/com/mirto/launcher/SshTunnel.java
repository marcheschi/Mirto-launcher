package com.mirto.launcher;

import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a free-form SSH local-port-forwarding command (e.g.
 * {@code ssh -L 8443:mirth.prova.it:8443 root@node01.picopalla.it}) into its
 * components and can build the corresponding ssh(1) argument list.
 *
 * The user is expected to type only the "ssh -L ..." part of the command; any
 * trailing token that is not an ssh option is treated as the optional jump
 * host (user@host). When no jump host is given, the destination host embedded
 * in the -L forwarding spec is used instead.
 */
public class SshTunnel {

    // -L [bind:]port:host:hostport   (IPv6 bind addresses allowed)
    private static final Pattern FORWARD_PATTERN =
            Pattern.compile("^(?:\\[(?:.+)\\]|[A-Za-z0-9_.-]+:)?(\\d{1,5}):(.+):(\\d{1,5})$");
    // user@host or plain host
    private static final Pattern HOST_PATTERN =
            Pattern.compile("^[A-Za-z0-9_.@%+-]+$");
    // values accepted by -N/-f/-p/-i options
    private static final Pattern PORT_OR_FILE_PATTERN =
            Pattern.compile("^\\d{1,5}$|^[^\\s]+$");

    public String localHost = "";     // optional bind address, usually empty
    public int localPort = 0;         // local port to listen on
    public String remoteHost = "";    // target host reached through the tunnel
    public int remotePort = 0;        // target port reached through the tunnel
    public String jumpHost = "";      // optional ssh server (user@host), may be empty
    public int sshPort = 22;          // -p <port>, if present
    public String identityFile = "";  // -i <keyfile>, if present
    public boolean doNotExecute = false; // -N
    public boolean forkAfterAuth = false; // -f

    /**
     * @return null when the command parses successfully, otherwise a
     * human-readable error message describing what is wrong.
     */
    public static String validate(String command) {
        if (StringUtils.isBlank(command)) {
            return "The SSH tunnel command is empty.";
        }
        List<String> tokens = tokenize(command.trim());
        if (tokens.isEmpty() || !tokens.get(0).equalsIgnoreCase("ssh")) {
            return "The command must start with \"ssh\".";
        }

        SshTunnel tunnel = new SshTunnel();
        boolean forwardFound = false;
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("-L")) {
                if (forwardFound) {
                    return "Only one -L forwarding is supported per connection.";
                }
                if (i + 1 >= tokens.size()) {
                    return "-L requires the forwarding spec: localport:host:hostport";
                }
                String spec = tokens.get(++i);
                Matcher m = FORWARD_PATTERN.matcher(spec);
                if (!m.matches()) {
                    return "Invalid -L spec \"" + spec + "\". Expected format: localport:host:hostport (e.g. 8443:mirth.prova.it:8443)";
                }
                applyForwardSpec(tunnel, spec, m);
                if (tunnel.localPort == 0 || tunnel.localPort > 65535
                        || tunnel.remotePort == 0 || tunnel.remotePort > 65535) {
                    return "Ports in the -L spec must be between 1 and 65535.";
                }
                forwardFound = true;
            } else if (t.equals("-p")) {
                if (i + 1 >= tokens.size() || !PORT_OR_FILE_PATTERN.matcher(tokens.get(i + 1)).matches()
                        || !tokens.get(i + 1).matches("\\d{1,5}")) {
                    return "-p requires a numeric port.";
                }
                tunnel.sshPort = Integer.parseInt(tokens.get(++i));
                if (tunnel.sshPort < 1 || tunnel.sshPort > 65535) {
                    return "-p port must be between 1 and 65535.";
                }
            } else if (t.equals("-i")) {
                if (i + 1 >= tokens.size() || StringUtils.isBlank(tokens.get(i + 1))) {
                    return "-i requires an identity file path.";
                }
                tunnel.identityFile = tokens.get(++i);
            } else if (t.equals("-N")) {
                tunnel.doNotExecute = true;
            } else if (t.equals("-f")) {
                tunnel.forkAfterAuth = true;
            } else if (t.startsWith("-")) {
                return "Unsupported ssh option \"" + t + "\". Supported: -L, -p, -i, -N, -f";
            } else {
                if (StringUtils.isNotBlank(tunnel.jumpHost)) {
                    return "Unexpected extra argument \"" + t + "\" after the jump host.";
                }
                if (!HOST_PATTERN.matcher(t).matches()) {
                    return "Invalid jump host \"" + t + "\". Expected user@host or host.";
                }
                tunnel.jumpHost = t;
            }
        }

        if (!forwardFound) {
            return "Missing -L forwarding (e.g. -L 8443:mirth.prova.it:8443).";
        }
        return null; // valid
    }

    /**
     * Checks whether the local end of a tunnel is already bound, so that
     * {@code ssh -L <port>:...} would fail with "Address already in use". This
     * performs a real socket bind (I/O) and is therefore kept separate from the
     * pure syntax check in {@link #validate(String)}.
     *
     * @param bindHost  the address the tunnel will listen on, or blank to mean ssh's
     *                  default (loopback); probed so a non-loopback bind is checked
     *                  against the right interface.
     * @param localPort the port the tunnel will listen on locally
     * @return null when the port appears free, otherwise a human-readable error message.
     */
    public static String checkLocalPortFree(String bindHost, int localPort) {
        if (localPort < 1 || localPort > 65535) {
            return "Invalid local port " + localPort + ".";
        }
        // ssh -L binds to loopback by default; honour an explicit bind address when given.
        String host = StringUtils.isNotBlank(bindHost) ? bindHost.trim() : "127.0.0.1";
        try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
            // No SO_REUSEADDR, otherwise an existing listener would not be reported as a conflict.
            probe.bind(new java.net.InetSocketAddress(host, localPort));
            return null; // free
        } catch (java.io.IOException e) {
            return "Local port " + localPort + " is already in use: close the other process using it, or pick a different -L local port.";
        }
    }

    /**
     * Parses an already-validated command into a tunnel object. Callers must
     * run {@link #validate(String)} first; this method assumes the syntax is
     * correct and never throws on malformed input.
     */
    public static SshTunnel parse(String command) {
        SshTunnel tunnel = new SshTunnel();
        if (StringUtils.isBlank(command)) {
            return tunnel;
        }
        List<String> tokens = tokenize(command.trim());
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("-L") && i + 1 < tokens.size()) {
                String spec = tokens.get(++i);
                Matcher m = FORWARD_PATTERN.matcher(spec);
                if (m.matches()) {
                    applyForwardSpec(tunnel, spec, m);
                }
            } else if (t.equals("-p") && i + 1 < tokens.size()) {
                try {
                    tunnel.sshPort = Integer.parseInt(tokens.get(++i));
                } catch (NumberFormatException ignored) {
                }
            } else if (t.equals("-i") && i + 1 < tokens.size()) {
                tunnel.identityFile = tokens.get(++i);
            } else if (t.equals("-N")) {
                tunnel.doNotExecute = true;
            } else if (t.equals("-f")) {
                tunnel.forkAfterAuth = true;
            } else if (!t.startsWith("-") && StringUtils.isBlank(tunnel.jumpHost)) {
                tunnel.jumpHost = t;
            }
        }
        return tunnel;
    }

    /**
     * Builds the ssh(1) argument list for the parsed command, always including
     * -N so no remote command is executed. If no jump host was provided, the
     * forwarding destination host is used as the ssh server.
     */
    public List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        cmd.add("-N");
        if (forkAfterAuth) {
            cmd.add("-f");
        }
        if (sshPort != 22) {
            cmd.add("-p");
            cmd.add(String.valueOf(sshPort));
        }
        if (StringUtils.isNotBlank(identityFile)) {
            cmd.add("-i");
            cmd.add(identityFile);
        }
        String forward = (StringUtils.isNotBlank(localHost) ? localHost + ":" : "")
                + localPort + ":" + remoteHost + ":" + remotePort;
        cmd.add("-L");
        cmd.add(forward);
        cmd.add(StringUtils.isNotBlank(jumpHost) ? jumpHost : remoteHost);
        return cmd;
    }

    /**
     * Rewrites the given codebase URL so it points at the local end of the
     * tunnel: host/port become localhost:localPort while the original scheme
     * (http or https) is preserved, since an SSH forward is a plain TCP pipe
     * and TLS passes through it unchanged.
     */
    public String rewriteUrl(String originalUrl) throws IllegalArgumentException {
        try {
            URI uri = new URI(originalUrl.trim());
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://localhost:").append(localPort);
            if (StringUtils.isNotEmpty(uri.getRawPath())) {
                sb.append(uri.getRawPath());
            }
            if (StringUtils.isNotEmpty(uri.getRawQuery())) {
                sb.append('?').append(uri.getRawQuery());
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse address \"" + originalUrl
                    + "\": " + e.getMessage());
        }
    }

    /**
     * Extracts bind address, local port, remote host and remote port from a -L
     * forwarding spec, handling the optional bind prefix: "[ipv6]:p:h:p",
     * "ipv4:p:h:p" (four colon-separated fields) and the plain "p:h:p" form.
     */
    private static void applyForwardSpec(SshTunnel tunnel, String spec, Matcher m) {
        if (spec.startsWith("[")) {
            int close = spec.indexOf(']');
            tunnel.localHost = spec.substring(1, close);
            String rest = spec.substring(close + 2);
            int first = rest.indexOf(':');
            int last = rest.lastIndexOf(':');
            tunnel.localPort = Integer.parseInt(rest.substring(0, first));
            tunnel.remoteHost = rest.substring(first + 1, last);
            tunnel.remotePort = Integer.parseInt(rest.substring(last + 1));
        } else if (spec.split(":", -1).length >= 4) {
            int first = spec.indexOf(':');
            int second = spec.indexOf(':', first + 1);
            int last = spec.lastIndexOf(':');
            tunnel.localHost = spec.substring(0, first);
            tunnel.localPort = Integer.parseInt(spec.substring(first + 1, second));
            tunnel.remoteHost = spec.substring(second + 1, last);
            tunnel.remotePort = Integer.parseInt(spec.substring(last + 1));
        } else {
            tunnel.localPort = Integer.parseInt(m.group(1));
            tunnel.remoteHost = m.group(2);
            tunnel.remotePort = Integer.parseInt(m.group(3));
        }
    }

    /** Simple whitespace tokenizer honouring double/single quotes. */
    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
