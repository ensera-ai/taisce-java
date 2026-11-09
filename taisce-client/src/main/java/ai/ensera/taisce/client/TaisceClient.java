// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        requireTransport(baseUrl);
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

    /**
     * Erases one person: every row the deployment holds for the subject, and hands back what it
     * measured afterwards.
     *
     * <p>Irreversible. A separate method from {@link #eraseSources} because the deployment refuses a
     * request naming both a subject and sources, or neither; two methods make that request
     * impossible to write. Needs a credential allowed to erase: a read-only key gets
     * {@link TaisceForbiddenException}. This is for application code that has decided, in code, that a
     * request to forget is genuine; it is not exposed to a model by either adapter, because text that
     * reaches a model is written by somebody else.
     *
     * @param reason recorded with the erasure and echoed nowhere else; may be null
     * @return the receipt, always clean
     * @throws TaisceErasureNotCleanException when rows still match; the exception carries the receipt
     * @throws IllegalArgumentException when the subject is empty
     */
    public ErasureReceipt eraseSubject(String dataSubjectId, String reason) {
        if (dataSubjectId == null || dataSubjectId.isBlank()) throw new IllegalArgumentException("an erasure by subject names the person it is for");
        ObjectNode body = JSON.createObjectNode();
        body.put("data_subject_id", dataSubjectId);
        return erase(body, reason);
    }

    /**
     * Erases what was derived from the named observations, without touching the rest of the person.
     * Irreversible; see {@link #eraseSubject} for the rest of the contract.
     *
     * @throws IllegalArgumentException when no observation is named or more than {@value #MAX_SOURCES} are
     */
    public ErasureReceipt eraseSources(List<String> sourceObservationIds, String reason) {
        ObjectNode body = JSON.createObjectNode();
        sources(body, sourceObservationIds, "an erasure by source");
        return erase(body, reason);
    }

    /**
     * Everything the deployment holds for one subject. Allowed to a read-only key.
     *
     * @throws TaisceExportTooLargeException when the subject holds more rows than one export returns
     */
    public DataExport exportSubject(String dataSubjectId) {
        if (dataSubjectId == null || dataSubjectId.isBlank()) throw new IllegalArgumentException("an export names the person it is for");
        ObjectNode body = JSON.createObjectNode();
        body.put("data_subject_id", dataSubjectId);
        return export(body);
    }

    /** Everything the deployment holds that was derived from the named observations. */
    public DataExport exportSources(List<String> sourceObservationIds) {
        ObjectNode body = JSON.createObjectNode();
        sources(body, sourceObservationIds, "an export by source");
        return export(body);
    }

    /** The most source observations one erasure or export may name; a larger set is several requests, each with its own receipt. */
    public static final int MAX_SOURCES = 10000;

    private static void sources(ObjectNode body, List<String> ids, String what) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException(what + " names at least one observation");
        if (ids.size() > MAX_SOURCES) throw new IllegalArgumentException(what + " names at most " + MAX_SOURCES + " observations; split it into more requests");
        ArrayNode list = body.putArray("source_observation_ids");
        for (String id : ids) {
            if (id == null || id.isBlank()) throw new IllegalArgumentException(what + " cannot name an empty observation id");
            list.add(id);
        }
    }

    private ErasureReceipt erase(ObjectNode body, String reason) {
        if (reason != null) body.put("reason", reason);
        JsonNode node = post("/v1/erasures", body, 200);
        ErasureReceipt receipt = new ErasureReceipt(node.path("request_id").asText(""), node.path("scope").asText(""),
                node.path("data_subject_id").asText(""), strings(node.path("source_observation_ids")),
                node.hasNonNull("completed_at") ? OffsetDateTime.parse(node.get("completed_at").asText()).toInstant() : null,
                counts(node.path("deleted")), counts(node.path("residual")), node.path("clean").asBoolean(false));
        // Clean is the deployment's word and the residual is its measurement; a receipt is clean only
        // if both agree, so a response whose flag says clean over a non-zero count is not believed.
        if (!receipt.clean() || receipt.residual().values().stream().anyMatch(n -> n != 0)) throw new TaisceErasureNotCleanException(receipt);
        return receipt;
    }

    private DataExport export(ObjectNode body) {
        JsonNode node = post("/v1/exports", body, 200);
        Map<String, List<JsonNode>> sections = new LinkedHashMap<>();
        node.path("sections").properties().forEach(e -> {
            List<JsonNode> rows = new ArrayList<>();
            e.getValue().forEach(rows::add);
            sections.put(e.getKey(), rows);
        });
        return new DataExport(node.path("scope").asText(""), node.path("data_subject_id").asText(""),
                strings(node.path("source_observation_ids")), sections, counts(node.path("rows")));
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Map<String, Integer> counts(JsonNode object) {
        Map<String, Integer> out = new LinkedHashMap<>();
        object.properties().forEach(e -> out.put(e.getKey(), e.getValue().asInt(0)));
        return out;
    }

    /**
     * Refuses an address the credential would cross the network in clear to.
     *
     * <p>The bearer token is the project's key, and a project holds every end user's memory, so one
     * mistyped or downgraded environment variable would otherwise hand it to anyone on the path.
     * Loopback is the exception because nothing leaves the machine; there is no opt-in flag for a
     * plaintext remote host, because nothing here needs one, and a flag nobody needs is a way round
     * the check that someone will eventually set. Hosts are matched literally and never resolved:
     * resolving would let a DNS answer decide whether the check passes.
     */
    private static void requireTransport(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the deployment address is not a URL", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null) throw new IllegalArgumentException("the deployment address needs a host");
        if (scheme.equals("https")) return;
        if (scheme.equals("http") && isLoopback(host)) return;
        throw new IllegalArgumentException("refusing " + scheme + "://" + host + ": the credential would cross the network in clear; "
                + "use https, or http only to localhost, 127.0.0.0/8 or ::1");
    }

    private static boolean isLoopback(String host) {
        if (host.equalsIgnoreCase("localhost")) return true;
        if (host.startsWith("[") && host.endsWith("]")) {
            try {
                // A literal containing a colon is parsed, never looked up.
                return InetAddress.getByName(host).isLoopbackAddress();
            } catch (UnknownHostException e) {
                return false;
            }
        }
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4 || !octets[0].equals("127")) return false;
        for (String octet : octets) {
            // No leading zeros: "0127" is octal to some parsers and decimal to others.
            if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) return false;
        }
        return true;
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
        int status = response.statusCode();
        String code = error.path("code").asText("unexpected_response");
        String message = error.path("message").asText("the deployment answered " + status);
        if (status == 403 && code.equals("forbidden")) throw new TaisceForbiddenException(status, code, message);
        if (status == 422 && code.equals("export_too_large")) throw new TaisceExportTooLargeException(status, code, message);
        throw new TaisceException(status, code, message);
    }
}
