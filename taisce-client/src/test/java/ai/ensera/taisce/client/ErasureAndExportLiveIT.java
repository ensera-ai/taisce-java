// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Erasure and export against a real deployment: the properties D24 promises, each test named for the
 * one it holds. Run with {@code mvn -Plive verify -pl taisce-client}.
 *
 * <p>Needs TAISCE_API, TAISCE_TOKEN (read_write) and TAISCE_READER_TOKEN (read_only). A missing one
 * fails the run rather than skipping it: a skipped test is a green run that asserted nothing, which
 * is the failure these tests exist to prevent. They write only under fresh subjects and erase what
 * they wrote, so they can run against a deployment that holds other data.
 */
class ErasureAndExportLiveIT {
    private static TaisceClient writer;
    private static TaisceClient reader;

    @BeforeAll
    static void deployment() {
        String api = required("TAISCE_API");
        writer = new TaisceClient(api, required("TAISCE_TOKEN"));
        reader = new TaisceClient(api, required("TAISCE_READER_TOKEN"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) fail(name + " is not set: the live tests need a deployment and fail without one");
        return value;
    }

    private static String observe(String subject, String words) {
        List<TaisceClient.Message> messages = List.of(new TaisceClient.Message("user", words), new TaisceClient.Message("assistant", "Noted."));
        return writer.observe(Memory.turnKey(subject, "live", messages), subject, null, messages).path("id").asText();
    }

    private static String freshSubject() {
        return "live-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static int total(java.util.Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    void anErasureBySubjectReturnsACleanReceiptAndLeavesNothingToExport() {
        String subject = freshSubject();
        observe(subject, "My badge number is 4471.");
        ErasureReceipt receipt = writer.eraseSubject(subject, "live test");
        assertTrue(receipt.clean());
        assertEquals(subject, receipt.dataSubjectId());
        assertTrue(total(receipt.deleted()) > 0, "something was deleted: " + receipt.deleted());
        assertFalse(receipt.residual().isEmpty(), "the receipt reports a residual per kind");
        receipt.residual().forEach((kind, n) -> assertEquals(0, n, kind));
        assertEquals(0, total(writer.exportSubject(subject).rows()));
    }

    @Test
    void anErasureBySourcesErasesTheObservationItNamesAndNotItsNeighbour() {
        String subject = freshSubject();
        String doomed = observe(subject, "Delete this sentence.");
        observe(subject, "Keep this one.");
        ErasureReceipt receipt = writer.eraseSources(List.of(doomed), "live test");
        assertTrue(receipt.clean());
        assertEquals(List.of(doomed), receipt.sourceObservationIds());
        assertEquals(0, total(writer.exportSources(List.of(doomed)).rows()));
        assertTrue(total(writer.exportSubject(subject).rows()) > 0, "the neighbour survived");
        writer.eraseSubject(subject, "live test cleanup");
    }

    @Test
    void aReadOnlyKeyIsRefusedAnErasureAndAllowedAnExport() {
        String subject = freshSubject();
        observe(subject, "Readers may look and not delete.");
        TaisceForbiddenException refused = assertThrows(TaisceForbiddenException.class, () -> reader.eraseSubject(subject, "live test"));
        assertEquals(403, refused.status());
        assertEquals("forbidden", refused.code());
        assertTrue(total(reader.exportSubject(subject).rows()) > 0, "the refused erasure left the data, and the reader can see it");
        writer.eraseSubject(subject, "live test cleanup");
    }

    @Test
    void anExportsRowCountsMatchItsSections() {
        String subject = freshSubject();
        observe(subject, "Count what you hand back.");
        DataExport export = writer.exportSubject(subject);
        assertEquals(subject, export.dataSubjectId());
        assertEquals(export.rows().keySet(), export.sections().keySet());
        export.sections().forEach((name, rows) -> assertEquals(export.rows().get(name), rows.size(), name));
        assertTrue(export.rows().getOrDefault("message", 0) >= 2, "the turn's two messages are in the export: " + export.rows());
        writer.eraseSubject(subject, "live test cleanup");
    }
}
