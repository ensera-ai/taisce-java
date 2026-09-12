// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// A session belongs to a person, and saying so is not optional. One credential opens a project and
// a project holds every end user's objects, so a store that forgets whose session it is makes one
// person's state reachable to another — and no amount of care in the application is a boundary.
class SessionStoreTest {
    /** A deployment that refuses everything with one status, which is all these cases need. */
    private static Artifacts refusing(int status) {
        return new Artifacts() {
            @Override
            public com.fasterxml.jackson.databind.JsonNode putArtifact(String id, String dataSubjectId, String kind,
                    String name, byte[] content, String expectedVersion) {
                throw new TaisceException(status, "refused", "refused");
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode getArtifact(String id, String dataSubjectId) {
                throw new TaisceException(status, "refused", "refused");
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode deleteArtifact(String id, String dataSubjectId, String expectedVersion) {
                throw new TaisceException(status, "refused", "refused");
            }
        };
    }

    @Test
    void aSessionWithoutAPersonIsRefusedBeforeItReachesTheDeployment() {
        SessionStore store = new SessionStore(refusing(404));
        assertThrows(IllegalArgumentException.class, () -> store.save("s1", " ", "{}", null));
        assertThrows(IllegalArgumentException.class, () -> store.save(" ", "marta", "{}", null));
        assertThrows(IllegalArgumentException.class, () -> store.load("s1", ""));
        assertThrows(IllegalArgumentException.class, () -> store.delete("s1", null, null));
        assertThrows(IllegalArgumentException.class, () -> new SessionStore(null));
    }

    @Test
    void aPersonWhoHasNotBeenHereBeforeIsNotAnError() {
        // A deployment that answers 404 for everything: the absence a first turn always meets.
        SessionStore store = new SessionStore(refusing(404));
        assertTrue(store.load("s1", "marta").isEmpty());
        store.delete("s1", "marta", null);

        // Anything else is the caller's to handle, not this class's to swallow.
        SessionStore broken = new SessionStore(refusing(500));
        assertEquals(500, assertThrows(TaisceException.class, () -> broken.load("s1", "marta")).status());
    }
}
