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
}
