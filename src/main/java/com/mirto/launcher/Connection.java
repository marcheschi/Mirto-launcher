package com.mirto.launcher;

import com.fasterxml.jackson.annotation.JsonInclude;

public class Connection {
    private String id;
    private String name;
    private String address;
    private String javaHome;
    private String javaHomeBundledValue;
    private String javaFxHome;
    private String heapSize;
    private String icon;
    private boolean showJavaConsole;
    private boolean sslProtocolsCustom;
    private String sslProtocols;
    private boolean sslCipherSuitesCustom;
    private String sslCipherSuites;
    private boolean useLegacyDHSettings;
    private String username;
    private String password;
    private String group;
    private String jvmOptions;
    private boolean closeWindow;
    private boolean clearCacheJars;
    private String customJavaHome;  // New field for custom Java home path
    private boolean useCustomJavaHome; // New field to track if custom Java home radio is selected
    private String notes; // Notes about the connection
    private boolean trustSelfSignedCertificate; // Per-connection opt-in to skip SSL verification
    private String sshTunnelCommand; // Optional "ssh -L ..." command used to jump through an SSL/SSH tunnel
    // When true, do NOT open our own SSH tunnel at launch: an external forward (e.g. stunnel)
    // already exposes the local port, so we just route through it. Defaults to false (enabled),
    // which keeps existing connection files working unchanged.
    private boolean tunnelDisabled;
    /** Per-connection probe timeout in ms (null = use the default). Omitted from JSON when unset. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    /** Timestamp of last successful launch (mssince epoch), 0 = never launched.
     *  Omitted from JSON when 0 so new connections dont get a bogus value.
     */
    @JsonInclude(JsonInclude.Include.NON_ZERO)
    private long lastLaunchedMs;
    private Integer probeTimeoutMs;
    // Constructors, getters, and setters
    public Connection() {}

    // Copy constructor: duplicates all properties of an existing connection
    public Connection(Connection other) {
        this.id = other.id;
        this.name = other.name;
        this.address = other.address;
        this.javaHome = other.javaHome;
        this.javaHomeBundledValue = other.javaHomeBundledValue;
        this.javaFxHome = other.javaFxHome;
        this.heapSize = other.heapSize;
        this.icon = other.icon;
        this.showJavaConsole = other.showJavaConsole;
        this.sslProtocolsCustom = other.sslProtocolsCustom;
        this.sslProtocols = other.sslProtocols;
        this.sslCipherSuitesCustom = other.sslCipherSuitesCustom;
        this.sslCipherSuites = other.sslCipherSuites;
        this.useLegacyDHSettings = other.useLegacyDHSettings;
        this.username = other.username;
        this.password = other.password;
        this.group = other.group;
        this.jvmOptions = other.jvmOptions;
        this.closeWindow = other.closeWindow;
        this.clearCacheJars = other.clearCacheJars;
        this.customJavaHome = other.customJavaHome;
        this.useCustomJavaHome = other.useCustomJavaHome;
        this.notes = other.notes;
        this.trustSelfSignedCertificate = other.trustSelfSignedCertificate;
        this.sshTunnelCommand = other.sshTunnelCommand;
        this.tunnelDisabled = other.tunnelDisabled;
        this.probeTimeoutMs = other.probeTimeoutMs;
        this.lastLaunchedMs = other.lastLaunchedMs;
    }

    /**
     * Convenience factory for a brand-new connection with sensible defaults.
     */
    public static Connection newDefault(String id, String name) {
        Connection conn = new Connection();
        conn.setId(id);
        conn.setName(name);
        conn.setAddress("https://localhost:8443");
        conn.setJavaHome("BUNDLED");
        conn.setJavaHomeBundledValue("Java 17");
        conn.setJavaFxHome("");
        conn.setHeapSize("512m");
        conn.setIcon("");
        conn.setUsername("");
        conn.setPassword("");
        conn.setGroup("");
        conn.setJvmOptions("");
        conn.setCustomJavaHome(null);
        conn.setUseCustomJavaHome(false);
        conn.setNotes("");
        conn.setLastLaunchedMs(0);
        return conn;
    }

    public Connection(String id, String name, String address, String javaHome, String javaHomeBundledValue, String javaFxHome,
                      String heapSize, String icon, boolean showJavaConsole, boolean sslProtocolsCustom, String sslProtocols,
                      boolean sslCipherSuitesCustom, String sslCipherSuites, boolean useLegacyDHSettings, String username, String password, String group, String jvmOptions, boolean closeWindow, boolean clearCacheJars, String customJavaHome, boolean useCustomJavaHome, String notes) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.javaHome = javaHome;
        this.javaHomeBundledValue = javaHomeBundledValue;
        this.javaFxHome = javaFxHome;
        this.heapSize = heapSize;
        this.icon = icon;
        this.showJavaConsole = showJavaConsole;
        this.sslProtocolsCustom = sslProtocolsCustom;
        this.sslProtocols = sslProtocols;
        this.sslCipherSuitesCustom = sslCipherSuitesCustom;
        this.sslCipherSuites = sslCipherSuites;
        this.useLegacyDHSettings = useLegacyDHSettings;
        this.username = username;
        this.password = password;
        this.group = group;
        this.jvmOptions = jvmOptions;
        this.closeWindow = closeWindow;
        this.clearCacheJars = clearCacheJars;
        this.customJavaHome = customJavaHome;
        this.useCustomJavaHome = useCustomJavaHome;
        this.notes = notes;
    }

    public String getId() { return id; }
    public void setId(String id) {
        this.id = id;
    }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getJavaHome() { return javaHome; }
    public void setJavaHome(String javaHome) { this.javaHome = javaHome; }
    public String getJavaHomeBundledValue() { return javaHomeBundledValue; }
    public void setJavaHomeBundledValue(String javaHomeBundledValue) { this.javaHomeBundledValue = javaHomeBundledValue; }
    public String getJavaFxHome() { return javaFxHome; }
    public void setJavaFxHome(String javaFxHome) { this.javaFxHome = javaFxHome; }
    public String getHeapSize() { return heapSize; }
    public void setHeapSize(String heapSize) { this.heapSize = heapSize; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public boolean isShowJavaConsole() { return showJavaConsole; }
    public void setShowJavaConsole(boolean showJavaConsole) { this.showJavaConsole = showJavaConsole; }
    public boolean isSslProtocolsCustom() { return sslProtocolsCustom; }
    public void setSslProtocolsCustom(boolean sslProtocolsCustom) { this.sslProtocolsCustom = sslProtocolsCustom; }
    public String getSslProtocols() { return sslProtocols; }
    public void setSslProtocols(String sslProtocols) { this.sslProtocols = sslProtocols; }
    public boolean isSslCipherSuitesCustom() { return sslCipherSuitesCustom; }
    public void setSslCipherSuitesCustom(boolean sslCipherSuitesCustom) { this.sslCipherSuitesCustom = sslCipherSuitesCustom; }
    public String getSslCipherSuites() { return sslCipherSuites; }
    public void setSslCipherSuites(String sslCipherSuites) { this.sslCipherSuites = sslCipherSuites; }
    public boolean isUseLegacyDHSettings() { return useLegacyDHSettings; }
    public void setUseLegacyDHSettings(boolean useLegacyDHSettings) { this.useLegacyDHSettings = useLegacyDHSettings; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getGroup() {
        return group;
    }
    public void setGroup(String group) {
        this.group = group;
    }
    public String getJvmOptions() { return jvmOptions; }
    public void setJvmOptions(String jvmOptions) { this.jvmOptions = jvmOptions; }
    public boolean isCloseWindow() { return closeWindow; }
    public void setCloseWindow(boolean closeWindow) { this.closeWindow = closeWindow; }
    public boolean isClearCacheJars() { return clearCacheJars; }
    public void setClearCacheJars(boolean clearCacheJars) { this.clearCacheJars = clearCacheJars; }
    public String getCustomJavaHome() { return customJavaHome; }
    public void setCustomJavaHome(String customJavaHome) { this.customJavaHome = customJavaHome; }
    public boolean isUseCustomJavaHome() { return useCustomJavaHome; }
    public void setUseCustomJavaHome(boolean useCustomJavaHome) { this.useCustomJavaHome = useCustomJavaHome; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isTrustSelfSignedCertificate() { return trustSelfSignedCertificate; }
    public void setTrustSelfSignedCertificate(boolean trustSelfSignedCertificate) { this.trustSelfSignedCertificate = trustSelfSignedCertificate; }
    public String getSshTunnelCommand() { return sshTunnelCommand; }
    public void setSshTunnelCommand(String sshTunnelCommand) { this.sshTunnelCommand = sshTunnelCommand; }
    /** @return true when the launcher must NOT open its own SSH tunnel (external forward active). */
    public boolean isTunnelDisabled() { return tunnelDisabled; }
    public void setTunnelDisabled(boolean tunnelDisabled) { this.tunnelDisabled = tunnelDisabled; }
    /** @return the per-connection probe timeout in ms, or {@code null} to use the default. */
    public Integer getProbeTimeoutMs() { return probeTimeoutMs; }
    public void setProbeTimeoutMs(Integer probeTimeoutMs) { this.probeTimeoutMs = probeTimeoutMs; }
    public long getLastLaunchedMs() { return lastLaunchedMs; }
    public void setLastLaunchedMs(long lastLaunchedMs) { this.lastLaunchedMs = lastLaunchedMs; }

    /** Builder for Connection to replace the 28-parameter constructor. */
    public static class Builder {
        private String id = java.util.UUID.randomUUID().toString();
        private String name = "";
        private String address = "";
        private String javaHome = "BUNDLED";
        private String javaHomeBundledValue = "Java 17";
        private String javaFxHome = "";
        private String heapSize = "512m";
        private String icon = "";
        private boolean showJavaConsole = false;
        private boolean sslProtocolsCustom = false;
        private String sslProtocols = "";
        private boolean sslCipherSuitesCustom = false;
        private String sslCipherSuites = "";
        private boolean useLegacyDHSettings = false;
        private String username = "";
        private String password = "";
        private String group = "";
        private String jvmOptions = "";
        private boolean closeWindow = false;
        private boolean clearCacheJars = false;
        private String customJavaHome = null;
        private boolean useCustomJavaHome = false;
        private String notes = "";
        private boolean trustSelfSignedCertificate = false;
        private String sshTunnelCommand = null;
        private boolean tunnelDisabled = false;
        private Integer probeTimeoutMs = null;
        private long lastLaunchedMs = 0;

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder address(String address) { this.address = address; return this; }
        public Builder javaHome(String javaHome) { this.javaHome = javaHome; return this; }
        public Builder javaHomeBundledValue(String v) { this.javaHomeBundledValue = v; return this; }
        public Builder javaFxHome(String v) { this.javaFxHome = v; return this; }
        public Builder heapSize(String heapSize) { this.heapSize = heapSize; return this; }
        public Builder icon(String icon) { this.icon = icon; return this; }
        public Builder showJavaConsole(boolean v) { this.showJavaConsole = v; return this; }
        public Builder sslProtocolsCustom(boolean v) { this.sslProtocolsCustom = v; return this; }
        public Builder sslProtocols(String v) { this.sslProtocols = v; return this; }
        public Builder sslCipherSuitesCustom(boolean v) { this.sslCipherSuitesCustom = v; return this; }
        public Builder sslCipherSuites(String v) { this.sslCipherSuites = v; return this; }
        public Builder useLegacyDHSettings(boolean v) { this.useLegacyDHSettings = v; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder password(String password) { this.password = password; return this; }
        public Builder group(String group) { this.group = group; return this; }
        public Builder jvmOptions(String jvmOptions) { this.jvmOptions = jvmOptions; return this; }
        public Builder closeWindow(boolean v) { this.closeWindow = v; return this; }
        public Builder clearCacheJars(boolean v) { this.clearCacheJars = v; return this; }
        public Builder customJavaHome(String v) { this.customJavaHome = v; return this; }
        public Builder useCustomJavaHome(boolean v) { this.useCustomJavaHome = v; return this; }
        public Builder notes(String notes) { this.notes = notes; return this; }
        public Builder trustSelfSignedCertificate(boolean v) { this.trustSelfSignedCertificate = v; return this; }
        public Builder sshTunnelCommand(String cmd) { this.sshTunnelCommand = cmd; return this; }
        public Builder tunnelDisabled(boolean v) { this.tunnelDisabled = v; return this; }
        public Builder probeTimeoutMs(Integer ms) { this.probeTimeoutMs = ms; return this; }
        public Builder lastLaunchedMs(long ms) { this.lastLaunchedMs = ms; return this; }

        public Connection build() {
            Connection c = new Connection();
            c.setId(id);
            c.setName(name);
            c.setAddress(address);
            c.setJavaHome(javaHome);
            c.setJavaHomeBundledValue(javaHomeBundledValue);
            c.setJavaFxHome(javaFxHome);
            c.setHeapSize(heapSize);
            c.setIcon(icon);
            c.setShowJavaConsole(showJavaConsole);
            c.setSslProtocolsCustom(sslProtocolsCustom);
            c.setSslProtocols(sslProtocols);
            c.setSslCipherSuitesCustom(sslCipherSuitesCustom);
            c.setSslCipherSuites(sslCipherSuites);
            c.setUseLegacyDHSettings(useLegacyDHSettings);
            c.setUsername(username);
            c.setPassword(password);
            c.setGroup(group);
            c.setJvmOptions(jvmOptions);
            c.setCloseWindow(closeWindow);
            c.setClearCacheJars(clearCacheJars);
            c.setCustomJavaHome(customJavaHome);
            c.setUseCustomJavaHome(useCustomJavaHome);
            c.setNotes(notes);
            c.setTrustSelfSignedCertificate(trustSelfSignedCertificate);
            c.setSshTunnelCommand(sshTunnelCommand);
            c.setTunnelDisabled(tunnelDisabled);
            c.setProbeTimeoutMs(probeTimeoutMs);
            c.setLastLaunchedMs(lastLaunchedMs);
            return c;
        }
    }
}
