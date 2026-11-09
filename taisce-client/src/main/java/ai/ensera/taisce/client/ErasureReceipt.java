// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What an erasure deleted and what it measured afterwards.
 *
 * <p>{@code residual} is the product: "done" is a claim, and a count of rows that still match, taken
 * after the delete in the same transaction, is a measurement. {@code clean} is true only when every
 * residual is zero. {@link TaisceClient#eraseSubject} and {@link TaisceClient#eraseSources} never
 * return a receipt that is not clean; they throw {@link TaisceErasureNotCleanException} carrying it.
 *
 * @param sourceObservationIds the observations a by-sources erasure named; empty for a by-subject one
 * @param deleted rows deleted, by kind
 * @param residual rows still matching, by kind
 */
public record ErasureReceipt(String requestId, String scope, String dataSubjectId, List<String> sourceObservationIds,
                             Instant completedAt, Map<String, Integer> deleted, Map<String, Integer> residual, boolean clean) {
    public ErasureReceipt {
        sourceObservationIds = List.copyOf(sourceObservationIds);
        deleted = Map.copyOf(deleted);
        residual = Map.copyOf(residual);
    }
}
