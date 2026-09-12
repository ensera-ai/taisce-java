// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client for a Taisce deployment over the v1 contract.
 *
 * <p>Knows nothing about any agent framework: the adapters are built on this, so a caller who wants
 * governed memory without a framework does not acquire one by asking. The JDK's HTTP client carries
 * the requests and Jackson reads the JSON, which both frameworks this repository serves already
 * depend on. Thread-safe and meant to be shared. The credential is a bearer token bound to one
 * project; it is sent nowhere but the deployment this client was built for and is never logged.
 *
 * <p>Responses are returned as JSON trees rather than typed records for the shapes the adapters
 * pass through unchanged (a recall bundle, a resolved citation): the contract adds fields over time,
 * and a tree carries a field this client was not written for, which is what the freeze promises a
 * client can rely on.
 */
public final class TaisceClient implements Artifacts {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http;
    private final String baseUrl;
    private final String token;
    private final Duration timeout;

    public TaisceClient(String baseUrl, String token) {
        this(baseUrl, token, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Duration.ofSeconds(30));
    }

    public TaisceClient(String baseUrl, String token, HttpClient http, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("a deployment address is required");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("a credential is required");
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.http = Objects.requireNonNull(http);
        this.timeout = Objects.requireNonNull(timeout);
    }

    /** The deployment's base URL, without a trailing slash. */
    public String baseUrl() { return baseUrl; }

    /** One message of a turn, with the role of whoever said it. */
    public record Message(String role, String content) {
    }

    /** Records a turn. Durable when this returns; formation follows. Returns the receipt: id, scope, log_offset. */
    public JsonNode observe(String idempotencyKey, String dataSubjectId, String occurredAt, List<Message> messages) {
        ObjectNode body = JSON.createObjectNode();
        body.put("idempotency_key", idempotencyKey);
        if (dataSubjectId != null && !dataSubjectId.isBlank()) body.put("data_subject_id", dataSubjectId);
        if (occurredAt != null) body.put("occurred_at", occurredAt);
        ArrayNode list = body.putArray("messages");
        for (Message m : messages) {
            list.addObject().put("role", m.role()).put("content", m.content());
        }
        return post("/v1/observations", body, 201);
    }

    /** How far behind memory is. */
    public Freshness freshness() {
        JsonNode node = send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/freshness")).GET(), 200);
        return new Freshness(node.path("scope").asText(""),
                node.hasNonNull("stored") ? node.get("stored").asLong() : null,
                node.hasNonNull("formed") ? node.get("formed").asLong() : null,
                node.path("parked").asInt(0));
    }

    /** Asks memory a question. {@code controls} are the contract's: max_characters, source_roles, hops, surfaces, themes, as_of, as_known_at. */
    public JsonNode recall(String question, String dataSubjectId, Map<String, Object> controls) {
        ObjectNode body = JSON.createObjectNode();
        body.put("question", question);
        if (dataSubjectId != null && !dataSubjectId.isBlank()) body.put("data_subject_id", dataSubjectId);
        if (controls != null) {
            for (Map.Entry<String, Object> e : controls.entrySet()) {
                if (e.getValue() != null) body.set(e.getKey(), JSON.valueToTree(e.getValue()));
            }
        }
        return post("/v1/recalls", body, 200);
    }

    /**
     * Searches the stored words themselves, for a question no fact answers.
     *
     * <p>This is the other half of retrieval and deliberately separate from {@link #recall}: recall
     * answers from what was inferred, this answers from what was said. A caller that wants grounding
     * without adopting the memory model uses only this.
     *
     * <p>The answer carries {@code approximate} and {@code covered_through_offset} because a passage
     * search reads an embedding generation, and a generation is built up to a point in the log.
     * Treating the answer as complete while the build is behind quotes an index, not the memory.
     */
    public JsonNode searchPassages(String question, Integer limit, String dataSubjectId, String sourceRole) {
        ObjectNode body = JSON.createObjectNode();
        body.put("question", question);
        if (limit != null) body.put("limit", limit);
        if (dataSubjectId != null && !dataSubjectId.isBlank()) body.put("data_subject_id", dataSubjectId);
        if (sourceRole != null && !sourceRole.isBlank()) body.put("source_role", sourceRole);
        return post("/v1/passages/search", body, 200);
    }

    /**
     * One subject's history under a budget, as the contract returns it: the newest turns verbatim,
     * the segments the deployment wrote over the rest, the watermark and the cost. No model call.
     */
    public JsonNode context(String dataSubjectId, Integer maxCharacters) {
        if (dataSubjectId == null || dataSubjectId.isBlank()) throw new IllegalArgumentException("a data subject is required: a context is one subject's history");
        ObjectNode body = JSON.createObjectNode();
        body.put("data_subject_id", dataSubjectId);
        if (maxCharacters != null) body.put("max_characters", maxCharacters);
        return post("/v1/contexts", body, 200);
    }

    /**
     * Stores an opaque object under a person, replacing the version it names.
     *
     * <p>The bytes are the application's and the deployment never interprets them. It holds them
     * under a project and a person, expires them with that person's retention and removes them with
     * that person's erasure — which is the whole reason to keep session state here rather than in an
     * application's own database, where a deletion request would have two places to sweep and a
     * counted residual for only one of them.
     */
    @Override
    public JsonNode putArtifact(String id, String dataSubjectId, String kind, String name, byte[] content, String expectedVersion) {
        ObjectNode body = JSON.createObjectNode();
        body.put("id", id);
        body.put("data_subject_id", dataSubjectId);
        body.put("kind", kind);
        body.put("content", content == null ? new byte[0] : content);
        if (name != null && !name.isBlank()) body.put("name", name);
        if (expectedVersion != null && !expectedVersion.isBlank()) body.put("expected_version", expectedVersion);
        return post("/v1/artifacts/put", body, 200);
    }

    /**
     * Reads one object, optionally requiring it to belong to the person named.
     *
     * <p>One credential opens a project, and a project holds every end user's objects. Naming the
     * subject makes "this one is theirs" a requirement the deployment enforces rather than a habit
     * the application keeps: another person's object is answered exactly as one that is not there.
     */
    @Override
    public JsonNode getArtifact(String id, String dataSubjectId) {
        ObjectNode body = JSON.createObjectNode();
        body.put("id", id);
        if (dataSubjectId != null && !dataSubjectId.isBlank()) body.put("data_subject_id", dataSubjectId);
        return post("/v1/artifacts/get", body, 200);
    }

    /** Removes one object, optionally requiring it to belong to the person named. */
    @Override
    public JsonNode deleteArtifact(String id, String dataSubjectId, String expectedVersion) {
        ObjectNode body = JSON.createObjectNode();
        body.put("id", id);
        if (dataSubjectId != null && !dataSubjectId.isBlank()) body.put("data_subject_id", dataSubjectId);
        if (expectedVersion != null && !expectedVersion.isBlank()) body.put("expected_version", expectedVersion);
        return post("/v1/artifacts/delete", body, 200);
    }

    /** Resolves a fact to its record, as the contract returns it. */
    public JsonNode resolveCitation(String factId) {
        ObjectNode body = JSON.createObjectNode();
        body.put("id", factId);
        return post("/v1/citations/resolve", body, 200);
    }

    /** Whether a recall returned nothing at all: no facts, no reports, no passages. */
    public static boolean isEmpty(JsonNode bundle) {
        return bundle.path("facts").isEmpty() && bundle.path("reports").isEmpty() && bundle.path("passages").isEmpty();
    }

    private JsonNode post(String path, JsonNode body, int success) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())), success);
    }

    private JsonNode send(HttpRequest.Builder request, int success) {
        HttpResponse<String> response;
        try {
            response = http.send(request.timeout(timeout).header("Authorization", "Bearer " + token).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new TaisceException(0, "unreachable", e.getMessage() == null ? "the deployment could not be reached" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaisceException(0, "interrupted", "the request was interrupted");
        }
        JsonNode node;
        try {
            node = response.body() == null || response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
        } catch (IOException e) {
            node = JSON.createObjectNode();
        }
        if (response.statusCode() == success) {
            return node;
        }
        JsonNode error = node.path("error");
        throw new TaisceException(response.statusCode(),
                error.path("code").asText("unexpected_response"),
                error.path("message").asText("the deployment answered " + response.statusCode()));
    }
}
