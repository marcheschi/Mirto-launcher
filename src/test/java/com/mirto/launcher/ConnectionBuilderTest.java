package com.mirto.launcher;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for the Connection.Builder to verify the replacement for the 28-param constructor. */
class ConnectionBuilderTest {

    @Test
    void builderCreatesValidConnection() {
        Connection c = new Connection.Builder()
                .name("Test Conn")
                .address("https://example.com:8443")
                .username("admin")
                .password("secret")
                .group("Prod")
                .heapSize("2g")
                .build();

        assertEquals("Test Conn", c.getName());
        assertEquals("https://example.com:8443", c.getAddress());
        assertEquals("admin", c.getUsername());
        assertEquals("secret", c.getPassword());
        assertEquals("Prod", c.getGroup());
        assertEquals("2g", c.getHeapSize());
        assertEquals("BUNDLED", c.getJavaHome());
        assertEquals("Java 17", c.getJavaHomeBundledValue());
    }

    @Test
    void builderDefaultsAreSensible() {
        Connection c = new Connection.Builder().build();
        assertEquals("https://localhost:8443", c.getAddress());
        assertEquals("512m", c.getHeapSize());
        assertFalse(c.isShowJavaConsole());
        assertFalse(c.isUseCustomJavaHome());
        assertNull(c.getCustomJavaHome());
        assertNull(c.getSshTunnelCommand());
        assertFalse(c.isTunnelDisabled());
        assertNull(c.getProbeTimeoutMs());
        assertEquals(0, c.getLastLaunchedMs());
    }

    @Test
    void builderCopiesAllFieldsFromExistingConnection() {
        Connection original = new Connection.Builder()
                .name("Original")
                .address("https://x.com:8443")
                .username("user")
                .password("pass")
                .group("Dev")
                .jvmOptions("-Dfoo=bar")
                .notes("test notes")
                .trustSelfSignedCertificate(true)
                .sshTunnelCommand("ssh -L 8443:x:8443 jump")
                .tunnelDisabled(true)
                .probeTimeoutMs(3000)
                .lastLaunchedMs(1234567890L)
                .build();

        Connection copy = new Connection(original);
        assertEquals(original.getName(), copy.getName());
        assertEquals(original.getAddress(), copy.getAddress());
        assertEquals(original.getUsername(), copy.getUsername());
        assertEquals(original.getPassword(), copy.getPassword());
        assertEquals(original.getGroup(), copy.getGroup());
        assertEquals(original.getJvmOptions(), copy.getJvmOptions());
        assertEquals(original.getNotes(), copy.getNotes());
        assertTrue(copy.isTrustSelfSignedCertificate());
        assertEquals(original.getSshTunnelCommand(), copy.getSshTunnelCommand());
        assertTrue(copy.isTunnelDisabled());
        assertEquals(Integer.valueOf(3000), copy.getProbeTimeoutMs());
        assertEquals(1234567890L, copy.getLastLaunchedMs());
    }

    @Test
    void builderWithAllFieldsSet() {
        Connection c = new Connection.Builder()
                .id("my-id")
                .name("Full")
                .javaHome("CUSTOM")
                .javaHomeBundledValue("Java 8")
                .customJavaHome("/opt/java")
                .useCustomJavaHome(true)
                .showJavaConsole(true)
                .closeWindow(true)
                .clearCacheJars(true)
                .trustSelfSignedCertificate(true)
                .build();

        assertEquals("my-id", c.getId());
        assertEquals("CUSTOM", c.getJavaHome());
        assertEquals("Java 8", c.getJavaHomeBundledValue());
        assertEquals("/opt/java", c.getCustomJavaHome());
        assertTrue(c.isUseCustomJavaHome());
        assertTrue(c.isShowJavaConsole());
        assertTrue(c.isCloseWindow());
        assertTrue(c.isClearCacheJars());
        assertTrue(c.isTrustSelfSignedCertificate());
    }
}
