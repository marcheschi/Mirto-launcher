package com.mirto.launcher;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the semantic validation of loaded/imported connections. */
class ConnectionsValidatorTest {

    private Connection conn(String name, String address, String heap) {
        Connection c = new Connection();
        c.setName(name);
        c.setAddress(address);
        if (heap != null) {
            c.setHeapSize(heap);
        }
        return c;
    }

    @Test
    void validListPasses() {
        List<Connection> ok = Arrays.asList(
                conn("Prod", "https://mirth.example.org:8443", "512m"),
                conn("Dev", "http://localhost:8080", "2g"));
        assertNull(ConnectionsValidator.validate(ok));
    }

    @Test
    void emptyAndNullListsPass() {
        assertNull(ConnectionsValidator.validate(Collections.emptyList()));
        assertNull(ConnectionsValidator.validate(null));
    }

    @Test
    void missingNameIsReported() {
        String problems = ConnectionsValidator.validate(
                Collections.singletonList(conn("", "https://mirth.example.org:8443", null)));
        assertTrue(problems.contains("missing name"), problems);
    }

    @Test
    void invalidAddressesAreReported() {
        List<Connection> bad = Arrays.asList(
                conn("A", "ftp://files.example.org", null),      // wrong scheme
                conn("B", "not a url", null),                     // not a URI
                conn("C", "https://", null));                     // no host
        String problems = ConnectionsValidator.validate(bad);
        assertTrue(problems.contains("\"A\""), problems);
        assertTrue(problems.contains("\"B\""), problems);
        assertTrue(problems.contains("\"C\""), problems);
    }

    @Test
    void bareHostAndPortWithoutSchemeAreAccepted() {
        // The launcher normalizes scheme-less addresses at launch time, so they are valid.
        List<Connection> ok = Arrays.asList(
                conn("Prod", "mirth.example.org:8443", null),
                conn("Local", "localhost:8080", null),
                conn("Ip", "192.168.1.5:8443", null));
        assertNull(ConnectionsValidator.validate(ok));
    }

    @Test
    void invalidHeapSizesAreReported() {
        List<Connection> bad = Arrays.asList(
                conn("A", "https://mirth.example.org:8443", "512 mb"), // space breaks -Xmx
                conn("B", "https://mirth.example.org:8443", "abc"));
        String problems = ConnectionsValidator.validate(bad);
        assertTrue(problems.contains("\"A\"") && problems.contains("heap size"), problems);
        assertTrue(problems.contains("\"B\""), problems);
    }

    @Test
    void validHeapSizesPass() {
        List<Connection> ok = Arrays.asList(
                conn("A", "https://mirth.example.org:8443", "512m"),
                conn("B", "https://mirth.example.org:8443", "2g"));
        assertNull(ConnectionsValidator.validate(ok));
    }

    @Test
    void allProblemsAreListed() {
        List<Connection> bad = Arrays.asList(
                conn("", "ftp://x", "10 kb"),
                conn("Ok", "https://mirth.example.org:8443", null));
        String problems = ConnectionsValidator.validate(bad);
        assertTrue(problems.contains("missing name"), problems);
        assertTrue(problems.contains("invalid address"), problems);
        assertTrue(problems.contains("heap size"), problems);
        // the valid entry must not be blamed
        assertEquals(0, problems.split("\"Ok\"").length - 1, "valid connection should have no problems");
    }
}
