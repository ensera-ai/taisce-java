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
  and the turn, the same bytes as the .NET and Python adapters'.
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

## How it is proved

The adapter conformance suite from the service repository runs against a live deployment with each
adapter's driver: the framework's own AI service or chat client with the adapter attached, and a
scripted model.

```sh
TAISCE_TOKEN=… TAISCE_API=https://memory.example TAISCE_CASES=../taisce/conformance/cases.json scripts/conformance.sh langchain4j
TAISCE_TOKEN=… TAISCE_API=https://memory.example TAISCE_CASES=../taisce/conformance/cases.json scripts/conformance.sh spring-ai
```

Eight cases, eight passes, for each. `mvn test` holds what needs no deployment.

## Versions

Built against LangChain4j 1.20.0 and Spring AI 2.0.1 on Java 21.
