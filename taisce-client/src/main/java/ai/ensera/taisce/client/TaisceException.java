// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

/**
 * A refusal from the deployment, carrying the contract's own code. Branch on {@link #code()}, never on the message.
 *
 * <p>Open to the refusals a caller has a reason to tell apart ({@link TaisceForbiddenException},
 * {@link TaisceExportTooLargeException}) and to the one that is not a refusal at all
 * ({@link TaisceErasureNotCleanException}); each is still a {@code TaisceException}, so code that
 * catches this type keeps catching all of them.
 */
public class TaisceException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final String code;

    public TaisceException(int status, String code, String message) {
        super(status + " " + code + ": " + message);
        this.status = status;
        this.code = code;
    }

    /** The HTTP status. */
    public int status() { return status; }

    /** The refusal code from the contract's closed vocabulary. */
    public String code() { return code; }
}
