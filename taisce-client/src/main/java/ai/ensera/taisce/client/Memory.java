// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

/**
 * What every adapter renders the same way: the memory message and the turn key. They live in the
 * client so both Java adapters share one implementation, and so the bytes agree with the .NET and
 * Python adapters', which derive them the same way.
 */
public final class Memory {
    /** The first line of every injected memory message; the conformance suite parses it. */
    public static final String MESSAGE_PREFIX = "taisce-memory/v1 untrusted";
    /** The key under which an injected message is marked untrusted, where the framework has a slot for it. */
    public static final String UNTRUSTED = "taisce.untrusted";
    private static final ObjectMapper JSON = new ObjectMapper();

    private Memory() {
    }

    /** The prefix line, then a JSON document with the watermark, the plan (what the recall did) and the recall's own arrays unchanged. */
    public static String render(Freshness watermark, JsonNode bundle) {
        ObjectNode document = JSON.createObjectNode();
        ObjectNode w = document.putObject("watermark");
        if (watermark.stored() == null) w.putNull("stored"); else w.put("stored", watermark.stored());
        if (watermark.formed() == null) w.putNull("formed"); else w.put("formed", watermark.formed());
        w.put("parked", watermark.parked());
        ObjectNode plan = document.putObject("plan");
        plan.set("controls", bundle.path("controls"));
        plan.set("degraded", bundle.path("degraded").isMissingNode() ? JSON.createArrayNode() : bundle.path("degraded"));
        plan.set("reach", bundle.path("reach"));
        document.set("facts", bundle.path("facts").isMissingNode() ? JSON.createArrayNode() : bundle.path("facts"));
        document.set("reports", bundle.path("reports").isMissingNode() ? JSON.createArrayNode() : bundle.path("reports"));
        document.set("passages", bundle.path("passages").isMissingNode() ? JSON.createArrayNode() : bundle.path("passages"));
        return MESSAGE_PREFIX + "\n" + document;
    }

    /**
     * The rendering of a context, in the memory message's shape: the watermark, the assembly's cost as
     * the plan, and the segments and turns unchanged. What replaces a history is the deployment's answer
     * and nothing an adapter wrote.
     */
    public static String renderContext(JsonNode context) {
        ObjectNode document = JSON.createObjectNode();
        JsonNode watermark = context.path("watermark");
        ObjectNode w = document.putObject("watermark");
        w.set("stored", watermark.hasNonNull("stored") ? watermark.get("stored") : NullNode.getInstance());
        w.set("formed", watermark.hasNonNull("formed") ? watermark.get("formed") : NullNode.getInstance());
        w.put("parked", watermark.path("parked").asInt(0));
        ObjectNode plan = document.putObject("plan");
        plan.put("characters", context.path("characters").asInt(0));
        plan.put("truncated", context.path("truncated").asBoolean(false));
        document.set("segments", context.path("segments").isArray() ? context.path("segments") : JSON.createArrayNode());
        document.set("turns", context.path("turns").isArray() ? context.path("turns") : JSON.createArrayNode());
        return MESSAGE_PREFIX + "\n" + document;
    }

    /** Whether a message's text is one an adapter injected, by its first line. */
    public static boolean isMemoryText(String text) {
        return text != null && text.startsWith(MESSAGE_PREFIX);
    }

    /** A turn's key: a version-5 UUID over the subject, the run and the messages, so a retried store is one observation. */
    public static String turnKey(String dataSubjectId, String runId, List<TaisceClient.Message> messages) {
        StringBuilder text = new StringBuilder()
                .append(dataSubjectId == null ? "" : dataSubjectId).append('\n')
                .append(runId == null ? "" : runId).append('\n');
        for (TaisceClient.Message m : messages) {
            text.append(m.role()).append('\n').append(m.content()).append('\n');
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
        long high = 0;
        long low = 0;
        for (int i = 0; i < 8; i++) high = (high << 8) | (digest[i] & 0xff);
        for (int i = 8; i < 16; i++) low = (low << 8) | (digest[i] & 0xff);
        return new UUID(high, low).toString();
    }
}
