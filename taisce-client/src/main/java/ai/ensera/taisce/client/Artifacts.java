// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The three operations a store of opaque objects needs, and nothing else.
 *
 * <p>Named separately from the client because {@link SessionStore} depends on exactly this much:
 * a narrower dependency is a smaller thing to reason about and a smaller thing to stand in for. The
 * client implements it; so does a test that needs a deployment which answers a particular way.
 */
public interface Artifacts {
    JsonNode putArtifact(String id, String dataSubjectId, String kind, String name, byte[] content, String expectedVersion);

    JsonNode getArtifact(String id, String dataSubjectId);

    JsonNode deleteArtifact(String id, String dataSubjectId, String expectedVersion);
}
