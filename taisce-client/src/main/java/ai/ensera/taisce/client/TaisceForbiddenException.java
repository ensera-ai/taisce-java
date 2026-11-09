// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

/**
 * The credential may not do this. A read-only key answers this to an erasure, and an agent key will
 * too: the boundary is the deployment's access level, not a rule this client keeps, so a caller who
 * sees it needs a different key and not a retry.
 */
public final class TaisceForbiddenException extends TaisceException {
    private static final long serialVersionUID = 1L;

    public TaisceForbiddenException(int status, String code, String message) {
        super(status, code, message);
    }
}
