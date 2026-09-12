// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Keeps an agent session where the memory it belongs to already lives.
 *
 * <p>A framework serializes a session and hands it back; none of them ship a store, so every
 * application invents one. Inventing it in the application's own database is the expensive mistake,
 * and the reason is governance rather than convenience: a session and the memory formed from it are
 * erased by the same request from the same person. Split across two systems, a deletion has two
 * places to sweep, one of which can produce a counted residual and one of which cannot, and the
 * honest answer to "is it gone" becomes "it is gone from the part we can measure".
 *
 * <p>So a session is an opaque object under the person it belongs to. The deployment never reads it,
 * expires it with that person's retention, and removes it with that person's erasure.
 *
 * <p><b>Whose session it is, is checked by the deployment.</b> One credential opens a project and a
 * project holds every end user's objects, so an application serving many people could hand one
 * person's session to another; nothing in the application is a boundary against that, only care.
 * Every call here names the subject, and the deployment answers another person's session exactly as
 * one that does not exist.
 *
 * <p><b>Concurrency is the caller's to declare.</b> A save carries the version it expects to replace
 * and the deployment refuses a stale one, so two turns of the same session cannot silently lose one
 * of their writes. Passing none is last-write-wins, which is a choice and never the default here.
 */
public final class SessionStore {
    private final Artifacts client;
    private final String kind;

    public SessionStore(Artifacts client) {
        this(client, "state");
    }

    public SessionStore(Artifacts client, String kind) {
        if (client == null) throw new IllegalArgumentException("a deployment is required");
        this.client = client;
        this.kind = (kind == null || kind.isBlank()) ? "state" : kind;
    }

    /** What a save returned: the version the next save should expect to replace. */
    public record Saved(String sessionId, String version) {}

    /** What a load returned: the serialized session as the framework wrote it, and its version. */
    public record Loaded(String state, String version) {}

    /** Stores a serialized session under the person it belongs to. */
    public Saved save(String sessionId, String dataSubjectId, String state, String expectedVersion) {
        require(sessionId, dataSubjectId);
        JsonNode receipt = client.putArtifact(sessionId, dataSubjectId, kind, "session",
                (state == null ? "" : state).getBytes(StandardCharsets.UTF_8), expectedVersion);
        return new Saved(receipt.path("id").asText(sessionId), receipt.path("version").asText(""));
    }

    /**
     * Reads a person's session back, or empty when there is none by that id for that person.
     *
     * <p>Empty rather than an exception, because "this person has not been here before" is the
     * ordinary first turn of every conversation and not an error anybody should have to catch.
     */
    public Optional<Loaded> load(String sessionId, String dataSubjectId) {
        require(sessionId, dataSubjectId);
        JsonNode stored;
        try {
            stored = client.getArtifact(sessionId, dataSubjectId);
        } catch (TaisceException e) {
            if (e.status() == 404) return Optional.empty();
            throw e;
        }
        byte[] content = stored.path("content").asText("").isEmpty()
                ? new byte[0]
                : decode(stored);
        return Optional.of(new Loaded(new String(content, StandardCharsets.UTF_8), stored.path("version").asText("")));
    }

    /** Removes a person's session. One that is not there is already removed. */
    public void delete(String sessionId, String dataSubjectId, String expectedVersion) {
        require(sessionId, dataSubjectId);
        try {
            client.deleteArtifact(sessionId, dataSubjectId, expectedVersion);
        } catch (TaisceException e) {
            if (e.status() != 404) throw e;
        }
    }

    private static byte[] decode(JsonNode stored) {
        try {
            return stored.path("content").binaryValue();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("the stored object was not readable bytes", e);
        }
    }

    /**
     * Both are required, and the subject most of all: without it the deployment cannot tell whose
     * session this is, and the check that makes one person's state unreachable to another is the one
     * thing this class exists to keep.
     */
    private static void require(String sessionId, String dataSubjectId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("a session needs an identity");
        }
        if (dataSubjectId == null || dataSubjectId.isBlank()) {
            throw new IllegalArgumentException(
                    "a session belongs to a person: without one, another person's session is reachable");
        }
    }
}
