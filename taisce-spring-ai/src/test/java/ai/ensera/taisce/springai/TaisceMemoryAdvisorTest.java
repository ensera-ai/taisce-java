// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.springai;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.ensera.taisce.client.TaisceClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What holds without a deployment: the refusals at construction. The rest is held by the conformance suite. */
class TaisceMemoryAdvisorTest {
    @Test
    void compactionNeedsASubjectAndANonNegativeTrigger() {
        TaisceClient client = new TaisceClient("http://127.0.0.1:1", "tsk");
        assertThrows(IllegalArgumentException.class, () -> new TaisceMemoryAdvisor(client, null, "run", Map.of(), null, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> new TaisceMemoryAdvisor(client, "s", "run", Map.of(), null, 0, -1));
        assertNotNull(new TaisceMemoryAdvisor(client, "s", "run", Map.of(), null, 0, 8));
        assertNotNull(new TaisceMemoryAdvisor(client, null, "run", Map.of(), null));
    }
}
