// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

/** A refusal from the deployment, carrying the contract's own code. Branch on {@link #code()}, never on the message. */
public final class TaisceException extends RuntimeException {
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
