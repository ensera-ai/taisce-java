// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What holds without a deployment: the rendering, the key and the client's refusals. */
class MemoryTest {
    @Test
    void theTurnKeyIsStableForATurnAndDifferentForAnotherAndTheSameBytesAsTheOtherAdapters() {
        List<TaisceClient.Message> turn = List.of(new TaisceClient.Message("user", "hello"), new TaisceClient.Message("assistant", "hi"));
        String key = Memory.turnKey("subject-1", "run-1", turn);
        assertEquals(key, Memory.turnKey("subject-1", "run-1", turn));
        assertEquals(5, UUID.fromString(key).version());
        assertNotEquals(key, Memory.turnKey("subject-2", "run-1", turn));
        assertNotEquals(key, Memory.turnKey("subject-1", "run-2", turn));
        assertNotEquals(key, Memory.turnKey("subject-1", "run-1", List.of(new TaisceClient.Message("user", "hello again"))));
    }

    /**
     * The vectors every adapter is checked against (testdata of the service's conformance suite),
     * including a character outside the Basic Multilingual Plane, which a UTF-16 length gets wrong.
     */
    @Test
    void everyTurnKeyVectorIsReproducedByteForByte() throws Exception {
        JsonNode file = new ObjectMapper().readTree(getClass().getResourceAsStream("/turn-keys.json"));
        assertEquals("taisce-turn-key/2", file.path("derivation").asText());
        assertTrue(file.path("vectors").size() >= 8, "the vectors file was truncated");
        for (JsonNode v : file.path("vectors")) {
            List<TaisceClient.Message> messages = new java.util.ArrayList<>();
            for (JsonNode m : v.path("messages")) messages.add(new TaisceClient.Message(m.path("role").asText(), m.path("content").asText()));
            assertEquals(v.path("key").asText(), Memory.turnKey(v.path("data_subject_id").asText(), v.path("run").asText(), messages), v.path("name").asText());
        }
    }

    @Test
    void twoTurnsWhoseLineJoinedTextIsIdenticalAreTwoKeys() throws Exception {
        JsonNode vectors = new ObjectMapper().readTree(getClass().getResourceAsStream("/turn-keys.json")).path("vectors");
        assertNotEquals(vectors.get(0).path("key").asText(), vectors.get(1).path("key").asText());
        assertNotEquals(keyOf(vectors.get(0)), keyOf(vectors.get(1)));
    }

    @Test
    void anAbsentSubjectAndRunAreTheEmptyString() {
        List<TaisceClient.Message> turn = List.of(new TaisceClient.Message("user", "hello"));
        assertEquals(Memory.turnKey("", "", turn), Memory.turnKey(null, null, turn));
    }

    private static String keyOf(JsonNode v) {
        List<TaisceClient.Message> messages = new java.util.ArrayList<>();
        for (JsonNode m : v.path("messages")) messages.add(new TaisceClient.Message(m.path("role").asText(), m.path("content").asText()));
        return Memory.turnKey(v.path("data_subject_id").asText(), v.path("run").asText(), messages);
    }

    @Test
    void theMemoryMessageIsThePrefixLineAndOneDocument() throws Exception {
        JsonNode bundle = new ObjectMapper().readTree("{\"controls\":{\"hops\":1},\"facts\":[{\"fact_id\":\"f1\"}],\"reports\":[],\"passages\":[],\"degraded\":[],\"reach\":{\"terms\":1}}");
        String rendered = Memory.render(new Freshness("p1", 4L, 3L, 0), bundle);
        assertTrue(rendered.startsWith(Memory.MESSAGE_PREFIX + "\n"));
        JsonNode document = new ObjectMapper().readTree(rendered.substring(rendered.indexOf('\n') + 1));
        assertEquals(4, document.path("watermark").path("stored").asLong());
        assertEquals(1, document.path("plan").path("controls").path("hops").asInt());
        assertEquals("f1", document.path("facts").get(0).path("fact_id").asText());
        assertTrue(Memory.isMemoryText(rendered));
        assertFalse(Memory.isMemoryText("hello"));
        assertFalse(TaisceClient.isEmpty(bundle));
        assertTrue(TaisceClient.isEmpty(new ObjectMapper().readTree("{\"facts\":[],\"reports\":[],\"passages\":[]}")));
    }

    @Test
    void theContextMessageIsThePrefixLineAndTheDeploymentsArraysUnchanged() throws Exception {
        JsonNode context = new ObjectMapper().readTree(
                "{\"watermark\":{\"stored\":12,\"formed\":12},\"segments\":[{\"level\":1,\"summary\":\"The office moved.\"}],"
                + "\"turns\":[{\"log_offset\":9,\"messages\":[{\"role\":\"user\",\"content\":\"Book it.\"}]}],\"characters\":140,\"truncated\":false}");
        String rendered = Memory.renderContext(context);
        assertTrue(rendered.startsWith(Memory.MESSAGE_PREFIX + "\n"));
        assertTrue(Memory.isMemoryText(rendered));
        JsonNode document = new ObjectMapper().readTree(rendered.substring(rendered.indexOf('\n') + 1));
        assertEquals(12, document.path("watermark").path("stored").asInt());
        assertEquals(0, document.path("watermark").path("parked").asInt());
        assertEquals(140, document.path("plan").path("characters").asInt());
        assertFalse(document.path("plan").path("truncated").asBoolean());
        assertEquals("The office moved.", document.path("segments").get(0).path("summary").asText());
        assertEquals("Book it.", document.path("turns").get(0).path("messages").get(0).path("content").asText());
        // A context with nothing in it renders empty arrays, never a missing field.
        JsonNode empty = new ObjectMapper().readTree("{\"watermark\":{},\"characters\":0,\"truncated\":false}");
        JsonNode bare = new ObjectMapper().readTree(Memory.renderContext(empty).substring(Memory.MESSAGE_PREFIX.length() + 1));
        assertTrue(bare.path("segments").isArray() && bare.path("turns").isArray() && bare.path("watermark").path("stored").isNull());
        assertThrows(IllegalArgumentException.class, () -> new TaisceClient("http://127.0.0.1:1", "tsk").context(" ", null));
    }

    @Test
    void aClientRefusesToBeBuiltWithoutADeploymentOrACredential() {
        assertThrows(IllegalArgumentException.class, () -> new TaisceClient("", "tsk"));
        assertThrows(IllegalArgumentException.class, () -> new TaisceClient("http://127.0.0.1:1", " "));
        assertEquals("http://127.0.0.1:1", new TaisceClient("http://127.0.0.1:1/", "tsk").baseUrl());
    }
}
