// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The credential is sent in clear to nobody but this machine. */
class TransportTest {
    @ParameterizedTest
    @ValueSource(strings = {"http://memory.example", "http://memory.example:8080", "ftp://localhost", "http://localhost.evil.example",
            "http://localhost@evil.example", "http://127.0.0.1.evil.example", "http://128.0.0.1", "http://127.0.0", "http://127.0.0.256",
            "http://0127.0.0.1", "http://[2001:db8::1]:8080", "http://10.0.0.5", "memory.example", "https://", "ws://localhost"})
    void aPlaintextOrNonLoopbackAddressIsRefusedBeforeAnyCredentialIsSent(String address) {
        assertThrows(IllegalArgumentException.class, () -> new TaisceClient(address, "tsk"));
        assertThrows(IllegalArgumentException.class,
                () -> new TaisceClient(address, "tsk", HttpClient.newHttpClient(), Duration.ofSeconds(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://memory.example", "HTTPS://memory.example:8443/base/", "http://localhost:18080", "http://LOCALHOST", "http://127.0.0.1:5",
            "http://127.9.9.9", "http://127.255.255.255", "http://[::1]:8080", "https://127.0.0.1"})
    void httpsAndLoopbackAreAccepted(String address) {
        assertDoesNotThrow(() -> new TaisceClient(address, "tsk"));
    }

    @Test
    void theRefusalSaysWhy() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new TaisceClient("http://memory.example", "tsk"));
        assertTrue(e.getMessage().contains("in clear"), e.getMessage());
        assertTrue(!e.getMessage().contains("tsk"), "the credential is never in the message");
    }
}
