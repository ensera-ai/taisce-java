// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

/**
 * The subject holds more rows than one export returns. The deployment refuses rather than
 * truncates, because an export that silently stops short is a false answer to an access request;
 * and it does not page, so neither does this client.
 */
public final class TaisceExportTooLargeException extends TaisceException {
    private static final long serialVersionUID = 1L;

    public TaisceExportTooLargeException(int status, String code, String message) {
        super(status, code, message);
    }
}
