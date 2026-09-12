#!/usr/bin/env bash
# Copyright 2026 The Taisce Authors
# SPDX-License-Identifier: Apache-2.0
#
# Runs the adapter conformance suite against a live deployment with one of this repository's
# adapters as the driver. Needs the `taisce` binary of the service, the suite file from the service
# repository, and a write-enabled credential in TAISCE_TOKEN.
#
#   TAISCE_TOKEN=… TAISCE_API=… TAISCE_CASES=../taisce/conformance/cases.json scripts/conformance.sh langchain4j
#   TAISCE_TOKEN=… TAISCE_API=… TAISCE_CASES=../taisce/conformance/cases.json scripts/conformance.sh spring-ai
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
adapter="${1:?langchain4j or spring-ai}"
: "${TAISCE_API:?the deployment address}"
: "${TAISCE_TOKEN:?a write-enabled credential}"
: "${TAISCE_CASES:?the path to conformance/cases.json from the service repository}"
taisce="${TAISCE_BIN:-taisce}"
case "$adapter" in
  langchain4j) module=taisce-langchain4j; main=ai.ensera.taisce.langchain4j.conformance.Driver ;;
  spring-ai) module=taisce-spring-ai; main=ai.ensera.taisce.springai.conformance.Driver ;;
  *) echo "unknown adapter $adapter" >&2; exit 2 ;;
esac
cd "$here"
mvn -q -B -DskipTests install >/dev/null
mvn -q -B -pl "$module" dependency:build-classpath -Dmdep.outputFile=target/classpath.txt >/dev/null
cp="$here/$module/target/classes:$here/taisce-client/target/classes:$(cat "$here/$module/target/classpath.txt")"
exec "$taisce" conformance --api "$TAISCE_API" --cases "$TAISCE_CASES" --driver "java -cp $cp $main"
