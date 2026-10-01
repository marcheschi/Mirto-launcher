package com.mirto.launcher;

import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Semantic validation for loaded/imported connections. Pure logic, no JavaFX:
 * catches data that parses fine as JSON but is unusable at launch time (missing
 * name/address, malformed heap size), so the UI can show a precise message
 * instead of failing later with an opaque JVM error.
 */
public final class ConnectionsValidator {

    /** Accepted heap sizes: digits followed by m/g, e.g. "512m", "2g". */
    private static final Pattern HEAP_SIZE = Pattern.compile("^\\d+[mg]$", Pattern.CASE_INSENSITIVE);
    /** Bare host or host:port (no scheme), which the launcher normalizes at launch time. */
    private static final Pattern BARE_HOST = Pattern.compile("^[A-Za-z0-9_.-]+(?::\\d{1,5})?$");

    private ConnectionsValidator() {
    }

    /**
     * @return null when the list is valid (an empty list is fine: first start),
     *         otherwise a human-readable description of every problem found.
     */
    public static String validate(List<Connection> connections) {
        if (connections == null || connections.isEmpty()) {
            return null;
        }
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < connections.size(); i++) {
            Connection c = connections.get(i);
            if (c == null) {
                problems.add("entry #" + (i + 1) + " is not an object");
                continue;
            }
            String label = StringUtils.isNotBlank(c.getName()) ? "\"" + c.getName() + "\"" : "entry #" + (i + 1);
            if (StringUtils.isBlank(c.getName())) {
                problems.add(label + ": missing name");
            }
            if (!isHttpUrl(c.getAddress())) {
                problems.add(label + ": invalid address \"" + c.getAddress() + "\" (expected http(s)://host:port)");
            }
            if (c.getHeapSize() != null && !HEAP_SIZE.matcher(c.getHeapSize()).matches()) {
                // A bad heap size makes the child JVM die at startup with an opaque error.
                problems.add(label + ": invalid heap size \"" + c.getHeapSize() + "\" (expected e.g. 512m or 2g)");
            }
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    private static boolean isHttpUrl(String address) {
        if (StringUtils.isBlank(address)) {
            return false;
        }
        String a = address.trim();
        // An explicit http(s) URL with a host...
        try {
            URI uri = new URI(a);
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && uri.getHost() != null) {
                return true;
            }
        } catch (URISyntaxException ignored) {
            // fall through to the bare host:port check below
        }
        // ...or a bare host / host:port, which the launcher normalizes at launch time.
        return BARE_HOST.matcher(a).matches();
    }
}
