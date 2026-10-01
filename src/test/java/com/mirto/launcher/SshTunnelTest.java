package com.mirto.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the SSH tunnel command parser/validator.
 */
class SshTunnelTest {

    @Test
    void validMinimalCommandParses() {
        assertNull(SshTunnel.validate("ssh -L 8443:localhost:8443 user@host"));
        SshTunnel t = SshTunnel.parse("ssh -L 8443:localhost:8443 user@host");
        assertEquals(8443, t.localPort);
        assertEquals("localhost", t.remoteHost);
        assertEquals(8443, t.remotePort);
        assertEquals("user@host", t.jumpHost);
        assertEquals(22, t.sshPort);
    }

    @Test
    void validCommandWithAllOptions() {
        String cmd = "ssh -p 2222 -i /home/u/key.pem -N -f -L 9443:mirth.prova.it:8443 root@node01";
        assertNull(SshTunnel.validate(cmd));
        SshTunnel t = SshTunnel.parse(cmd);
        assertEquals(2222, t.sshPort);
        assertEquals("/home/u/key.pem", t.identityFile);
        assertTrue(t.doNotExecute);
        assertTrue(t.forkAfterAuth);
        assertEquals(9443, t.localPort);
        assertEquals("mirth.prova.it", t.remoteHost);
        assertEquals(8443, t.remotePort);
        assertEquals("root@node01", t.jumpHost);
    }

    @Test
    void bindAddressIsCaptured() {
        SshTunnel t = SshTunnel.parse("ssh -L 127.0.0.1:8443:host:8443 jump");
        assertEquals("127.0.0.1", t.localHost);
        assertEquals(8443, t.localPort);
    }

    @Test
    void blankCommandIsInvalid() {
        assertNotNull(SshTunnel.validate(""));
        assertNotNull(SshTunnel.validate("   "));
        assertNotNull(SshTunnel.validate(null));
    }

    @Test
    void nonSshCommandIsInvalid() {
        assertNotNull(SshTunnel.validate("scp -L 8443:h:p user@host"));
    }

    @Test
    void missingForwardIsInvalid() {
        assertNotNull(SshTunnel.validate("ssh -p 2222 user@host"));
    }

    @Test
    void invalidForwardSpecIsRejected() {
        assertNotNull(SshTunnel.validate("ssh -L 8443:host user@host"));
        assertNotNull(SshTunnel.validate("ssh -L abc:host:8443 user@host"));
        assertNotNull(SshTunnel.validate("ssh -L 0:host:8443 user@host"));
        assertNotNull(SshTunnel.validate("ssh -L 70000:host:8443 user@host"));
    }

    @Test
    void doubleForwardIsRejected() {
        assertNotNull(SshTunnel.validate("ssh -L 1:h:1 -L 2:h:2 user@host"));
    }

    @Test
    void unsupportedOptionIsRejected() {
        assertNotNull(SshTunnel.validate("ssh -X -L 8443:h:8443 user@host"));
    }

    @Test
    void invalidSshPortIsRejected() {
        assertNotNull(SshTunnel.validate("ssh -p abc -L 8443:h:8443 user@host"));
        assertNotNull(SshTunnel.validate("ssh -p 99999 -L 8443:h:8443 user@host"));
    }

    @Test
    void quotedTokensAreHandled() {
        SshTunnel t = SshTunnel.parse("ssh -i \"/path with spaces/key.pem\" -L 8443:h:8443 user@host");
        assertEquals("/path with spaces/key.pem", t.identityFile);
    }

    @Test
    void buildCommandRoundTrips() {
        SshTunnel t = SshTunnel.parse("ssh -p 2222 -i key.pem -L 9443:mirth:8443 root@node01");
        assertEquals("ssh -N -p 2222 -i key.pem -L 9443:mirth:8443 root@node01",
                String.join(" ", t.buildCommand()));
    }

    @Test
    void buildCommandFallsBackToRemoteHost() {
        SshTunnel t = SshTunnel.parse("ssh -L 9443:mirth:8443");
        assertEquals("ssh -N -L 9443:mirth:8443 mirth", String.join(" ", t.buildCommand()));
    }

    @Test
    void rewriteUrlKeepsSchemeAndPath() {
        SshTunnel t = SshTunnel.parse("ssh -L 8443:internal:8443 jump");
        assertEquals("https://localhost:8443/api/server/version",
                t.rewriteUrl("https://internal:8443/api/server/version"));
        assertEquals("http://localhost:8443",
                t.rewriteUrl("http://internal:8443"));
    }

    @Test
    void rewriteUrlRejectsGarbage() {
        SshTunnel t = SshTunnel.parse("ssh -L 8443:h:8443 j");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> t.rewriteUrl("::::not a url"));
    }

    @Test
    void extraArgumentAfterJumpHostIsRejected() {
        assertNotNull(SshTunnel.validate("ssh -L 8443:h:8443 jump extra"));
    }

    @Test
    void falsePositiveCheck() {
        // guard: assertTrue/assertFalse actually run
        assertTrue(SshTunnel.validate("ssh -L 1:h:1 j") == null);
        assertFalse(SshTunnel.validate("ssh -L 1:h:1 j") != null);
    }

    @Test
    void occupiedLocalPortIsDetected() throws Exception {
        // Occupy a loopback port, then verify the checker flags it as in use (blank bind = loopback).
        try (java.net.ServerSocket occupied = new java.net.ServerSocket(0, 50,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            int busyPort = occupied.getLocalPort();
            String err = SshTunnel.checkLocalPortFree("", busyPort);
            assertNotNull(err);
            assertTrue(err.contains(String.valueOf(busyPort)));
        }
    }

    @Test
    void freeLocalPortIsAccepted() throws Exception {
        // Grab an ephemeral port, release it, then verify the checker sees it as free.
        int port;
        try (java.net.ServerSocket tmp = new java.net.ServerSocket(0, 50,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            port = tmp.getLocalPort();
        }
        assertNull(SshTunnel.checkLocalPortFree("", port));
    }

    @Test
    void invalidLocalPortIsRejected() {
        assertNotNull(SshTunnel.checkLocalPortFree("", 0));
        assertNotNull(SshTunnel.checkLocalPortFree("", 70000));
    }
}
