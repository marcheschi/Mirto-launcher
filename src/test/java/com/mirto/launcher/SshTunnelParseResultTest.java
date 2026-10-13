package com.mirto.launcher;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for the consolidated SshTunnel.ParseResult and the unified parse method. */
class SshTunnelParseResultTest {

    @Test
    void parseResultOkHoldsTunnel() {
        SshTunnel.ParseResult r = SshTunnel.ParseResult.ok(new SshTunnel());
        assertTrue(r.isValid());
        assertNull(r.error);
        assertNotNull(r.tunnel);
    }

    @Test
    void parseResultErrorHoldsMessage() {
        SshTunnel.ParseResult r = SshTunnel.ParseResult.error("bad command");
        assertFalse(r.isValid());
        assertEquals("bad command", r.error);
        assertNull(r.tunnel);
    }

    @Test
    void validateDelegatesToParse() {
        assertNull(SshTunnel.validate("ssh -L 8443:localhost:8443 user@host"));
        assertNotNull(SshTunnel.validate("not ssh"));
    }

    @Test
    void parseReturnsValidResultForGoodCommand() {
        SshTunnel.ParseResult r = SshTunnel.parse("ssh -L 8443:host:8443 user@host");
        assertTrue(r.isValid());
        assertEquals(8443, r.tunnel.localPort);
        assertEquals("host", r.tunnel.remoteHost);
        assertEquals("user@host", r.tunnel.jumpHost);
    }

    @Test
    void parseReturnsErrorForBadCommand() {
        SshTunnel.ParseResult r = SshTunnel.parse("garbage");
        assertFalse(r.isValid());
        assertNotNull(r.error);
        assertTrue(r.error.contains("ssh"));
    }
}

    @Test
    void parseCapturesAllOptions() {
        SshTunnel.ParseResult r = SshTunnel.parse("ssh -p 2222 -i /key.pem -N -f -L 9443:mirth:8443 root@node01");
        assertTrue(r.isValid());
        assertEquals(2222, r.tunnel.sshPort);
        assertEquals("/key.pem", r.tunnel.identityFile);
        assertTrue(r.tunnel.doNotExecute);
        assertTrue(r.tunnel.forkAfterAuth);
        assertEquals(9443, r.tunnel.localPort);
        assertEquals("mirth", r.tunnel.remoteHost);
        assertEquals("root@node01", r.tunnel.jumpHost);
    }
