<!-- Copyright 2026 The Taisce Authors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Taisce for Java

Three modules, one repository, released to Maven Central on its cadence:

| Module | What it is |
|---|---|
| `taisce-client` | The v1 contract of a Taisce deployment over the JDK's HTTP client, with Jackson for JSON and no agent framework |
| `taisce-langchain4j` | The LangChain4j adapter: a `ChatModel` that wraps the application's model, injects governed memory into each request and records the turn when the final answer comes back |
| `taisce-spring-ai` | The Spring AI adapter: a `BaseAdvisor` that does the same around a `ChatClient` call |

Both adapters keep their framework as a `provided` dependency: the application chooses the
framework version, and the adapter compiles against the one it was written for.

```java
var client = new TaisceClient("https://memory.example", token);

// LangChain4j: wrap the model; keep whatever chat memory the application already uses.
var withMemory = new TaisceChatModel(model, client, "alice", null, Map.of(), null);
var assistant = AiServices.builder(Assistant.class).chatModel(withMemory).chatMemory(MessageWindowChatMemory.withMaxMessages(20)).build();

// Spring AI: one advisor.
var chat = ChatClient.builder(model).defaultAdvisors(new TaisceMemoryAdvisor(client, "alice", null, Map.of(), null)).build();
```

## What the adapters do, and hold to

- **Memory arrives as exactly one user message, marked untrusted.** Never a system message: both
  frameworks pin a system message first, so text an attacker got into memory would become a permanent
  instruction. Spring AI marks the message in its metadata; LangChain4j in the user message's
  attributes, and names it `taisce`. The message is the line
  `taisce-memory/v1 untrusted` and a JSON document carrying the freshness watermark, what the recall
  did, and the recall's facts, reports and passages unchanged. It is handed to the model and is never
  history.
- **Recall failure is not fatal.** The agent runs without memory; `onError` is how an operator still
  finds out.
- **Write failure is never silent.** A failed store throws.
- **A failed turn is not a memory.** The turn is stored when the model's final answer arrives; a
  model that threw never produces one.
- **Only what people said becomes memory.** The person's message and the assistant's final text. In
  LangChain4j the wrapped model sees the tool loop's requests and results and stores none of them; in
  Spring AI, tools execute inside the model call and never reach an advisor.
- **A retried store is one observation.** The idempotency key is derived from the subject, the run
  and the turn, the same bytes as the .NET and Python adapters': every field is a length-prefixed
  netstring, so no content can move a boundary and make two different turns one key. A turn that was
  being retried across the upgrade to 0.2.0 is stored twice, once, because every key changed.
- **Compaction is replacement, never a summary.** With a positive `compactAfterMessages`, a request
  holding more non-system messages than that is rebuilt as the system messages, one untrusted user
  message carrying the deployment's context (its segments and verbatim turns unchanged) and the
  person's message. Neither adapter summarises, and neither rewrites the application's chat memory:
  the request the model sees is what changes, so while the memory stays over the trigger every call
  fetches the context once. A context that cannot be fetched leaves the request as it was. In Spring
  AI the advisor must be ordered after any chat-memory advisor, so it sees the history that advisor
  adds.

```java
new TaisceMemoryAdvisor(client, "alice", runId, Map.of(), onError, 0, 40);
new TaisceChatModel(model, client, "alice", runId, Map.of(), onError, 40);
```

LangChain4j's chat memory is read before the new message is added to it, which is why the adapter is
a model decorator and not a `ChatMemory`: a memory cannot inject for the turn in hand.

## The client: address, erasure and export

**The address must be `https`.** Every `TaisceClient` constructor refuses any other scheme unless the
host is loopback (`localhost`, an address in `127.0.0.0/8`, or `::1`), because the bearer token is the
project's key and would cross the network in clear. There is no switch to allow it.

**Erasure and export are methods on the client, and nowhere else.** They are for application code that
has decided, in code, that a request to forget or to export is genuine; neither adapter exposes them
to a model, and a tool that did would let anyone whose text reaches the model ask for an erasure.

```java
ErasureReceipt receipt = client.eraseSubject("alice", "request from the person");    // all of a person
ErasureReceipt part = client.eraseSources(List.of(observationId), "wrong person");   // what came from these turns
DataExport export = client.exportSubject("alice");                                   // allowed to a read-only key
DataExport slice = client.exportSources(List.of(observationId));
```

- Subject and sources are separate methods because the deployment refuses a request naming both or
  neither; one method with two optional arguments could write that request. An empty subject, or no
  source ids, or more than 10 000, is an `IllegalArgumentException` before anything is sent.
- **An erasure that leaves rows throws `TaisceErasureNotCleanException`**, carrying the whole
  `ErasureReceipt` (`receipt()`, with `deleted` and `residual` counts per kind). A receipt returned as
  a plain value lets code that never reads it tell a person their data is gone while rows remain. A
  receipt that returns is clean.
- A key that may not erase gets `TaisceForbiddenException` (403 `forbidden`), and an export larger
  than one response gets `TaisceExportTooLargeException` (422 `export_too_large`): the deployment
  does not page exports and neither does the client. Both extend `TaisceException`.
- `DataExport.sections()` holds each section's rows as raw JSON (`message`, `chunk`, `fact`, ...), and
  `rows()` the count of each, so an export can be compared with a receipt.

Every read-write key can erase every subject in its project. These methods make that reachable from
code; they do not widen it.

## How it is proved

The adapter conformance suite from the service repository runs against a live deployment with each
adapter's driver: the framework's own AI service or chat client with the adapter attached, and a
scripted model.

```sh
TAISCE_TOKEN=… TAISCE_API=https://memory.example TAISCE_CASES=../taisce/conformance/cases.json scripts/conformance.sh langchain4j
TAISCE_TOKEN=… TAISCE_API=https://memory.example TAISCE_CASES=../taisce/conformance/cases.json scripts/conformance.sh spring-ai
```

Twelve cases, twelve passes, for each (the turn-key and erasure cases need a service release that
carries them; `conformance.yml` pins the release it was proved against). `mvn verify` holds what needs
no deployment, including every vector of the turn key.

The client's erasure and export are proved against a deployment too, with a read-write and a
read-only key, and the tests **fail rather than skip** without them:

```sh
TAISCE_API=http://localhost:18080 TAISCE_TOKEN=… TAISCE_READER_TOKEN=… mvn -Plive verify -pl taisce-client
```

CI also fails on a known vulnerability in anything the modules resolve (osv-scanner, on every push
and daily), and every module resolves one Jackson version: `mvn dependency:tree -Dincludes=com.fasterxml.jackson.core`.

## Versions

Built against LangChain4j 1.23.0 and Spring AI 2.0.2 on Java 21.
