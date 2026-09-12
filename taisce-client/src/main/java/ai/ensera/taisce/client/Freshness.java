// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.client;

/** How far behind memory is: the highest offset stored, the highest formed (null until the first turn forms), the parked count. */
public record Freshness(String scope, Long stored, Long formed, int parked) {
}
