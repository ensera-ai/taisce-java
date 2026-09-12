// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

// The on-ramp: a search over what was said, mapped onto a retrieval seam, adding nothing. Each
// result keeps enough identity to cite, and an answer from an index still being built says so — a
// model handed an incomplete answer as a complete one answers confidently from it.
class PassagesTest {
    private static final String ANSWER = """
            {"generation_id":"g1","through_offset":9,"covered_through_offset":4,
             "build_state":"building","approximate":true,
             "passages":[{"chunk_id":"c1","source_id":"o1","ordinal":2,"role":"user",
                          "preview":"I work at Ensera.","similarity":0.81,
                          "occurred_at":"2026-01-01T00:00:00Z","preview_complete":true}]}""";

    @Test
    void everyPassageBecomesOneCitableResultAndAnIncompleteIndexSaysSo() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode answer = json.readTree(ANSWER);

        List<Passages.Result> mapped = Passages.map(answer);
        assertEquals(2, mapped.size());
        assertEquals("I work at Ensera.", mapped.get(0).text());
        assertEquals("turn o1 message 2", mapped.get(0).sourceName());
        assertEquals("c1", mapped.get(0).raw().path("chunk_id").asText());
        assertEquals(Passages.APPROXIMATE_NOTICE, mapped.get(1).text());

        assertEquals(1, Passages.map(answer, false).size());
        JsonNode complete = json.readTree(ANSWER.replace("\"approximate\":true", "\"approximate\":false"));
        assertEquals(1, Passages.map(complete).size());
        // A caveat with no results is itself a result.
        com.fasterxml.jackson.databind.node.ObjectNode nothing = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(ANSWER);
        nothing.putArray("passages");
        assertTrue(Passages.map(nothing).isEmpty());
    }
}
