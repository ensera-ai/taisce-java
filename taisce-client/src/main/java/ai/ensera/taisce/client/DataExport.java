// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * Everything the deployment holds for one subject or for some source observations.
 *
 * <p>The rows are raw JSON, not a shaped view: an export has to include a column a migration added
 * without anybody remembering to add it to a class here. {@code rows} counts each section, so an
 * export can be compared with an {@link ErasureReceipt} without parsing either.
 *
 * @param sections the rows of each section, keyed by singular name ({@code message}, {@code chunk}, {@code fact}, ...)
 * @param rows the number of rows in each section
 */
public record DataExport(String scope, String dataSubjectId, List<String> sourceObservationIds,
                         Map<String, List<JsonNode>> sections, Map<String, Integer> rows) {
    public DataExport {
        sourceObservationIds = List.copyOf(sourceObservationIds);
        sections = Map.copyOf(sections);
        rows = Map.copyOf(rows);
    }
}
