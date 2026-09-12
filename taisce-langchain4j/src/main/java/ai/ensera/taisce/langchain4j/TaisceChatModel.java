// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.langchain4j;

import ai.ensera.taisce.client.Freshness;
import ai.ensera.taisce.client.Memory;
import ai.ensera.taisce.client.TaisceClient;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * A LangChain4j {@link ChatModel} that wraps the application's model: it injects governed memory
 * into each request and records the turn when the model's final answer comes back.
 *
 * <p>LangChain4j has no context-provider interface, and its chat memory is read before the new
 * message is added to it, so a memory cannot inject for the turn in hand. The model is the one seam
 * that sees the whole request and the whole answer, and a model that wraps another is how LangChain4j
 * layers behaviour. This maps onto it with nothing of its own: the conversation's history is the
 * application's {@code ChatMemory}, untouched.
 *
 * <p><b>The role rule.</b> Memory is one {@link UserMessage} named {@code taisce} and marked untrusted
 * in its attributes, never a {@link SystemMessage}: LangChain4j pins a system message first and never
 * evicts it, so text an attacker got into memory would become a permanent instruction. The message
 * is in the request the wrapped model sees and nowhere else: never in the chat memory, never stored.
 *
 * <p><b>Failure policy is asymmetric.</b> A recall that cannot reach the deployment injects nothing
 * and throws nothing. A failed store throws, because a lost turn is invisible until a subject access
 * request asks for it.
 *
 * <p><b>Compaction is replacement of the request.</b> When {@code compactAfterMessages} is positive
 * and the request holds more non-system messages than that, the request the wrapped model sees is
 * rebuilt as the system messages, one untrusted user message carrying the deployment's context
 * unchanged, and the person's current message. Nothing is summarised here, and the application's
 * chat memory is not rewritten: while it stays over the trigger, every turn fetches the context once.
 * A context that cannot be fetched leaves the request as it was and reports.
 *
 * <p><b>Store only on success, and only what people said.</b> The turn is stored when the wrapped
 * model answers with text and no tool requests; a model that threw never answers. A request in the
 * tool loop carries tool requests and results, and none of them is stored: the turn is the person's
 * last message and the final answer.
 */
public final class TaisceChatModel implements ChatModel {
    private final ChatModel delegate;
    private final TaisceClient client;
    private final String dataSubjectId;
    private final String runId;
    private final Map<String, Object> controls;
    private final BiConsumer<String, Exception> onError;
    private final int compactAfterMessages;

    public TaisceChatModel(ChatModel delegate, TaisceClient client, String dataSubjectId, String runId,
            Map<String, Object> controls, BiConsumer<String, Exception> onError) {
        this(delegate, client, dataSubjectId, runId, controls, onError, 0);
    }

    /**
     * @param compactAfterMessages the number of non-system messages in a request past which the
     *     history is replaced by the deployment's context; zero leaves compaction off. Positive
     *     requires a data subject.
     */
    public TaisceChatModel(ChatModel delegate, TaisceClient client, String dataSubjectId, String runId,
            Map<String, Object> controls, BiConsumer<String, Exception> onError, int compactAfterMessages) {
        this.delegate = Objects.requireNonNull(delegate);
        this.client = Objects.requireNonNull(client);
        this.dataSubjectId = dataSubjectId;
        this.runId = runId;
        this.controls = controls == null ? Map.of() : controls;
        this.onError = onError;
        if (compactAfterMessages < 0) throw new IllegalArgumentException("compactAfterMessages must not be negative");
        if (compactAfterMessages > 0 && (dataSubjectId == null || dataSubjectId.isBlank())) {
            throw new IllegalArgumentException("a data subject is required to compact: a context is one subject's history");
        }
        this.compactAfterMessages = compactAfterMessages;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        int at = lastUserIndex(messages);
        String question = at < 0 ? null : text((UserMessage) messages.get(at));
        // Memory is fetched for the person's message, once per turn: a request inside the tool
        // loop carries a tool result last, and asking memory again would be a second injection.
        if (at >= 0 && at == messages.size() - 1) {
            // The person's message is last, so this is the turn's first request and not one inside
            // the tool loop: compaction, when due, and memory happen here and once.
            if (compactAfterMessages > 0 && nonSystem(messages) > compactAfterMessages) {
                List<ChatMessage> replaced = compact(messages, at);
                if (replaced != null) {
                    messages = replaced;
                    at = lastUserIndex(messages);
                }
            }
            String rendered = provide(question);
            if (rendered != null) {
                messages.add(at, UserMessage.builder().name("taisce")
                        .contents(List.of(TextContent.from(rendered)))
                        .attributes(Map.of(Memory.UNTRUSTED, true)).build());
            }
        }
        ChatResponse response = delegate.chat(ChatRequest.builder().messages(messages).parameters(request.parameters()).build());
        AiMessage answer = response.aiMessage();
        if (question != null && !question.isBlank() && answer != null && !answer.hasToolExecutionRequests()
                && answer.text() != null && !answer.text().isBlank()) {
            store(question, answer.text());
        }
        return response;
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    private String provide(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        try {
            Freshness watermark = client.freshness();
            JsonNode bundle = client.recall(question, dataSubjectId, controls);
            return TaisceClient.isEmpty(bundle) ? null : Memory.render(watermark, bundle);
        } catch (RuntimeException e) {
            report("recall", e);
            return null;
        }
    }

    /**
     * The request with its history replaced by the deployment's context: the system messages, one
     * untrusted user message carrying the context, and the person's message. Null when the context
     * could not be fetched, which is reported and not fatal.
     */
    private List<ChatMessage> compact(List<ChatMessage> messages, int at) {
        String rendered;
        try {
            Object ceiling = controls.get("max_characters");
            rendered = Memory.renderContext(client.context(dataSubjectId, ceiling instanceof Number n ? n.intValue() : null));
        } catch (RuntimeException e) {
            report("context", e);
            return null;
        }
        List<ChatMessage> kept = new ArrayList<>();
        for (ChatMessage m : messages.subList(0, at)) {
            if (m instanceof SystemMessage) {
                kept.add(m);
            }
        }
        kept.add(UserMessage.builder().name("taisce").contents(List.of(TextContent.from(rendered)))
                .attributes(Map.of(Memory.UNTRUSTED, true)).build());
        kept.addAll(messages.subList(at, messages.size()));
        return kept;
    }

    private static int nonSystem(List<ChatMessage> messages) {
        int n = 0;
        for (ChatMessage m : messages) {
            if (!(m instanceof SystemMessage)) n++;
        }
        return n;
    }

    private void store(String user, String assistant) {
        List<TaisceClient.Message> turn = List.of(new TaisceClient.Message("user", user), new TaisceClient.Message("assistant", assistant));
        try {
            client.observe(Memory.turnKey(dataSubjectId, runId, turn), dataSubjectId,
                    DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS)), turn);
        } catch (RuntimeException e) {
            report("observe", e);
            throw e;
        }
    }

    private void report(String stage, Exception e) {
        if (onError != null) {
            onError.accept(stage, e);
        }
    }

    /** The index of the person's last message that is not an injected memory, or -1. */
    public static int lastUserIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage u && !Memory.isMemoryText(text(u)) && !text(u).isBlank()) {
                return i;
            }
        }
        return -1;
    }

    /** The text of a user message, or an empty string. */
    public static String text(UserMessage message) {
        return message.hasSingleText() ? message.singleText() : "";
    }
}
