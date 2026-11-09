// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

/**
 * An erasure ran and rows still match. Raised instead of returned because a receipt handed back as a
 * plain value lets an application that never reads it tell a person their data is gone while rows
 * remain; throwing makes ignoring it impossible, and the whole receipt is here for whoever handles it.
 */
public final class TaisceErasureNotCleanException extends TaisceException {
    private static final long serialVersionUID = 1L;
    // A record is not Serializable; the exception is, so the receipt is held but not written out.
    private final transient ErasureReceipt receipt;

    public TaisceErasureNotCleanException(ErasureReceipt receipt) {
        super(200, "erasure_not_clean", "the erasure left rows matching " + residualSummary(receipt));
        this.receipt = receipt;
    }

    /** The receipt the deployment returned, including the residual counts. */
    public ErasureReceipt receipt() { return receipt; }

    private static String residualSummary(ErasureReceipt receipt) {
        StringBuilder text = new StringBuilder();
        receipt.residual().entrySet().stream().filter(e -> e.getValue() > 0).sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> text.append(text.length() == 0 ? "" : ", ").append(e.getKey()).append('=').append(e.getValue()));
        return text.length() == 0 ? "the request" : text.toString();
    }
}
