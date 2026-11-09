// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The branches a real deployment cannot be made to take on demand: a receipt that is not clean, and
 * the contract's refusals. The happy paths are proved against a deployment in the live tests; a
 * deployment that erases correctly never returns a residual, so the refusal to hand one back as a
 * value needs a server that answers one. The server here answers a fixed body and checks nothing
 * about the deployment, only what this client does with each answer.
 */
class ErasureRefusalsTest {
    private HttpServer server;
    private TaisceClient client;
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile int status;
    private volatile String answer;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new TaisceClient("http://127.0.0.1:" + server.getAddress().getPort(), "tsk");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String receipt(boolean clean, int residualMessages) {
        return "{\"request_id\":\"r1\",\"scope\":\"p1\",\"data_subject_id\":\"alice\",\"completed_at\":\"2026-11-09T10:00:00.123456789Z\","
                + "\"deleted\":{\"message\":2},\"residual\":{\"message\":" + residualMessages + ",\"chunk\":0},\"clean\":" + clean + "}";
    }

    @Test
    void anErasureThatLeavesRowsThrowsAndTheExceptionCarriesTheWholeReceipt() {
        status = 200;
        answer = receipt(false, 1);
        TaisceErasureNotCleanException e = assertThrows(TaisceErasureNotCleanException.class, () -> client.eraseSubject("alice", "test"));
        assertFalse(e.receipt().clean());
        assertEquals(1, e.receipt().residual().get("message"));
        assertEquals(2, e.receipt().deleted().get("message"));
        assertEquals("alice", e.receipt().dataSubjectId());
        assertTrue(e.getMessage().contains("message=1"), e.getMessage());
        assertInstanceOf(TaisceException.class, e);
    }

    @Test
    void aReceiptSayingCleanOverANonZeroResidualIsNotBelieved() {
        status = 200;
        answer = receipt(true, 3);
        assertEquals(3, assertThrows(TaisceErasureNotCleanException.class, () -> client.eraseSubject("alice", null)).receipt().residual().get("message"));
    }

    @Test
    void aCleanReceiptIsReturnedWithTheReasonAndTheSubjectSentAndNothingElse() throws Exception {
        status = 200;
        answer = receipt(true, 0);
        ErasureReceipt r = client.eraseSubject("alice", "a request from the person");
        assertTrue(r.clean());
        assertEquals("2026-11-09T10:00:00.123456789Z", r.completedAt().toString());
        assertEquals("/v1/erasures", requestPath.get());
        JsonNode sent = new ObjectMapper().readTree(requestBody.get());
        assertEquals("alice", sent.path("data_subject_id").asText());
        assertEquals("a request from the person", sent.path("reason").asText());
        // A by-subject request must not carry the other form, or the deployment refuses it.
        assertFalse(sent.has("source_observation_ids"));
    }

    @Test
    void anErasureBySourcesSendsTheIdsAndNoSubject() throws Exception {
        status = 200;
        answer = receipt(true, 0).replace("\"clean\"", "\"source_observation_ids\":[\"0190f3a0-0000-7000-8000-000000000001\"],\"clean\"");
        ErasureReceipt r = client.eraseSources(List.of("0190f3a0-0000-7000-8000-000000000001"), "cleanup");
        assertEquals(List.of("0190f3a0-0000-7000-8000-000000000001"), r.sourceObservationIds());
        JsonNode sent = new ObjectMapper().readTree(requestBody.get());
        assertFalse(sent.has("data_subject_id"));
        assertEquals(1, sent.path("source_observation_ids").size());
    }

    @Test
    void aForbiddenKeyAndAnOversizedExportAreTheirOwnExceptions() {
        status = 403;
        answer = "{\"error\":{\"code\":\"forbidden\",\"message\":\"this key may not erase\"}}";
        TaisceForbiddenException forbidden = assertThrows(TaisceForbiddenException.class, () -> client.eraseSubject("alice", null));
        assertEquals(403, forbidden.status());
        assertEquals("forbidden", forbidden.code());
        status = 422;
        answer = "{\"error\":{\"code\":\"export_too_large\",\"message\":\"too many rows\"}}";
        assertEquals(422, assertThrows(TaisceExportTooLargeException.class, () -> client.exportSubject("alice")).status());
        // A 422 with another code is the generic refusal: only the contract's own code earns the type.
        answer = "{\"error\":{\"code\":\"invalid_request\",\"message\":\"no\"}}";
        TaisceException other = assertThrows(TaisceException.class, () -> client.exportSubject("alice"));
        assertFalse(other instanceof TaisceExportTooLargeException);
    }

    @Test
    void anExportKeepsEverySectionRawAndItsRowCounts() {
        status = 200;
        answer = "{\"scope\":\"p1\",\"data_subject_id\":\"alice\",\"sections\":{\"message\":[{\"id\":1,\"future_column\":true},{\"id\":2}],\"fact\":[]},\"rows\":{\"message\":2,\"fact\":0}}";
        DataExport export = client.exportSubject("alice");
        assertEquals("/v1/exports", requestPath.get());
        assertEquals(2, export.sections().get("message").size());
        assertTrue(export.sections().get("message").get(0).path("future_column").asBoolean());
        assertEquals(export.rows().get("message"), export.sections().get("message").size());
        assertEquals(0, export.rows().get("fact"));
    }

    @Test
    void whatTheServiceWouldRefuseIsRefusedBeforeAnythingIsSent() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= TaisceClient.MAX_SOURCES; i++) tooMany.add("id-" + i);
        List<String> atTheLimit = tooMany.subList(0, TaisceClient.MAX_SOURCES);
        assertThrows(IllegalArgumentException.class, () -> client.eraseSubject(" ", "r"));
        assertThrows(IllegalArgumentException.class, () -> client.eraseSubject(null, "r"));
        assertThrows(IllegalArgumentException.class, () -> client.eraseSources(List.of(), "r"));
        assertThrows(IllegalArgumentException.class, () -> client.eraseSources(null, "r"));
        assertThrows(IllegalArgumentException.class, () -> client.eraseSources(tooMany, "r"));
        assertThrows(IllegalArgumentException.class, () -> client.eraseSources(java.util.Arrays.asList("a", null), "r"));
        assertThrows(IllegalArgumentException.class, () -> client.exportSubject(""));
        assertThrows(IllegalArgumentException.class, () -> client.exportSources(List.of()));
        assertThrows(IllegalArgumentException.class, () -> client.exportSources(tooMany));
        assertEquals(null, requestPath.get(), "nothing reached the server");
        // The limit itself is allowed through.
        status = 200;
        answer = receipt(true, 0);
        client.eraseSources(atTheLimit, "r");
        assertEquals("/v1/erasures", requestPath.get());
    }
}
