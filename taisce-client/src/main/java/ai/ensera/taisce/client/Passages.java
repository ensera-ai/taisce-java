// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The on-ramp that asks nothing of a developer's beliefs.
 *
 * <p>The memory advisor is the product, and it asks something: that a memory service should be
 * forming facts from conversations. Many developers do not accept that yet, and saying it louder is
 * not a strategy. What they do want is grounding — a search over what was actually said, returned
 * with enough identity to cite — and the deployment already serves exactly that.
 *
 * <p>So this maps a passage search onto the shape every retrieval seam in this ecosystem wants, and
 * adds nothing: no recall, no storing, no memory message. A framework's own retriever wraps these
 * three fields without asking the deployment anything further, and a developer who starts here and
 * later wants entities changes one line.
 */
public final class Passages {
    private Passages() {}

    /** What the model is told when the index behind an answer is still being built. */
    public static final String APPROXIMATE_NOTICE =
            "These passages come from an index that is still being built, so they are some of what "
                    + "was said and not necessarily all of it.";

    /** One retrieved passage: the words, where they came from, and the row behind them. */
    public record Result(String text, String sourceName, JsonNode raw) {}

    /**
     * Turns what the deployment returned into results a retriever can consume.
     *
     * <p>Separated from the call so it can be held to its rules without a deployment: a mapping
     * tested through an HTTP stub is a test of the stub.
     *
     * <p>The source is named by what it is in this system — a turn and a position in it — rather
     * than by a document title this deployment does not have. A caller that wants the surrounding
     * words resolves the citation through the same contract.
     */
    public static List<Result> map(JsonNode answer, boolean sayWhenApproximate) {
        List<Result> out = new ArrayList<>();
        if (answer == null) return out;
        for (JsonNode passage : answer.path("passages")) {
            out.add(new Result(
                    passage.path("preview").asText(""),
                    "turn " + passage.path("source_id").asText("") + " message " + passage.path("ordinal").asInt(0),
                    passage));
        }
        // A model handed an incomplete answer as though it were complete answers confidently from
        // it. A caveat with no results, though, is itself a result, so it is only ever appended to
        // something.
        if (sayWhenApproximate && answer.path("approximate").asBoolean(false) && !out.isEmpty()) {
            out.add(new Result(APPROXIMATE_NOTICE, "taisce", com.fasterxml.jackson.databind.node.NullNode.getInstance()));
        }
        return out;
    }

    /** The same, saying so when the index is behind. */
    public static List<Result> map(JsonNode answer) {
        return map(answer, true);
    }
}
