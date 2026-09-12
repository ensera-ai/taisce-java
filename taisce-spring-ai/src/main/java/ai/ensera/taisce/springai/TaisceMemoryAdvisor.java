// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.springai;

import ai.ensera.taisce.client.Freshness;
import ai.ensera.taisce.client.Memory;
import ai.ensera.taisce.client.TaisceClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A Spring AI advisor that injects governed memory before the model call and records the turn after
 * it.
 *
 * <p>Spring AI's seam for "do something before and after the model call" is the advisor, and
 * {@link BaseAdvisor} is the two-hook form of it. This maps onto it with nothing of its own.
 *
 * <p><b>The role rule.</b> Memory is one {@link UserMessage} marked untrusted in its metadata, never
 * a system message. It is placed before the person's last message in the prompt and is never part of
 * a chat memory: the advisor mutates the request the model sees, not the conversation an application
 * keeps.
 *
 * <p><b>Failure policy is asymmetric.</b> A recall that cannot reach the deployment injects nothing
 * and throws nothing. A failed store throws, because a lost turn is invisible until a subject access
 * request asks for it.
 *
 * <p><b>Compaction is replacement of the request.</b> When {@code compactAfterMessages} is positive
 * and the prompt holds more non-system messages than that, {@link #before} asks the deployment for
 * the subject's context and rebuilds the prompt as the system messages, one untrusted user message
 * carrying the context unchanged, and the person's current message. Nothing is summarised here, and
 * the application's chat memory is not rewritten: while it stays over the trigger, every call fetches
 * the context once. A context that cannot be fetched leaves the prompt as it was and reports.
 *
 * <p><b>Store only on success, and only what people said.</b> {@link #after} runs when the model
 * answered; a model that threw unwinds the chain before it. What is stored is the person's last
 * message and the assistant's final text. Tool calls and their results are executed inside the model
 * call in Spring AI and never reach an advisor, so by construction they are not memory.
 */
public final class TaisceMemoryAdvisor implements BaseAdvisor {
    private final TaisceClient client;
    private final String dataSubjectId;
    private final String runId;
    private final Map<String, Object> controls;
    private final BiConsumer<String, Exception> onError;
    private final int order;
    private final int compactAfterMessages;

    public TaisceMemoryAdvisor(TaisceClient client, String dataSubjectId, String runId, Map<String, Object> controls,
            BiConsumer<String, Exception> onError) {
        this(client, dataSubjectId, runId, controls, onError, 0);
    }

    public TaisceMemoryAdvisor(TaisceClient client, String dataSubjectId, String runId, Map<String, Object> controls,
            BiConsumer<String, Exception> onError, int order) {
        this(client, dataSubjectId, runId, controls, onError, order, 0);
    }

    /**
     * @param compactAfterMessages the number of non-system messages in the prompt past which the
     *     history is replaced by the deployment's context; zero leaves compaction off. Positive
     *     requires a data subject.
     */
    public TaisceMemoryAdvisor(TaisceClient client, String dataSubjectId, String runId, Map<String, Object> controls,
            BiConsumer<String, Exception> onError, int order, int compactAfterMessages) {
        this.client = Objects.requireNonNull(client);
        this.dataSubjectId = dataSubjectId;
        this.runId = runId;
        this.controls = controls == null ? Map.of() : controls;
        this.onError = onError;
        this.order = order;
        if (compactAfterMessages < 0) throw new IllegalArgumentException("compactAfterMessages must not be negative");
        if (compactAfterMessages > 0 && (dataSubjectId == null || dataSubjectId.isBlank())) {
            throw new IllegalArgumentException("a data subject is required to compact: a context is one subject's history");
        }
        this.compactAfterMessages = compactAfterMessages;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
        int at = lastUserIndex(messages);
        if (at < 0) {
            return request;
        }
        boolean compacted = false;
        if (compactAfterMessages > 0 && nonSystem(messages) > compactAfterMessages) {
            List<Message> replaced = compact(messages, at);
            if (replaced != null) {
                messages = replaced;
                at = lastUserIndex(messages);
                compacted = true;
            }
        }
        String question = messages.get(at).getText();
        String rendered;
        try {
            Freshness watermark = client.freshness();
            JsonNode bundle = client.recall(question, dataSubjectId, controls);
            if (TaisceClient.isEmpty(bundle)) {
                // Nothing to say costs the model nothing to read; a compaction already made stands.
                return compacted ? withMessages(request, messages) : request;
            }
            rendered = Memory.render(watermark, bundle);
        } catch (RuntimeException e) {
            report("recall", e);
            return compacted ? withMessages(request, messages) : request;
        }
        messages.add(at, UserMessage.builder().text(rendered).metadata(Map.of(Memory.UNTRUSTED, true)).build());
        return withMessages(request, messages);
    }

    private static ChatClientRequest withMessages(ChatClientRequest request, List<Message> messages) {
        Prompt prompt = request.prompt().mutate().messages(messages).build();
        return request.mutate().prompt(prompt).build();
    }

    /**
     * The prompt with its history replaced by the deployment's context: the system messages, one
     * untrusted user message carrying the context, and the person's message with what follows it.
     * Null when the context could not be fetched, which is reported and not fatal.
     */
    private List<Message> compact(List<Message> messages, int at) {
        String rendered;
        try {
            Object ceiling = controls.get("max_characters");
            rendered = Memory.renderContext(client.context(dataSubjectId, ceiling instanceof Number n ? n.intValue() : null));
        } catch (RuntimeException e) {
            report("context", e);
            return null;
        }
        List<Message> kept = new ArrayList<>();
        for (Message m : messages.subList(0, at)) {
            if (m.getMessageType() == MessageType.SYSTEM) {
                kept.add(m);
            }
        }
        kept.add(UserMessage.builder().text(rendered).metadata(Map.of(Memory.UNTRUSTED, true)).build());
        kept.addAll(messages.subList(at, messages.size()));
        return kept;
    }

    private static int nonSystem(List<Message> messages) {
        int n = 0;
        for (Message m : messages) {
            if (m.getMessageType() != MessageType.SYSTEM) n++;
        }
        return n;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String user = lastUserText(response.context());
        String assistant = assistantText(response);
        if (user == null || user.isBlank() || assistant == null || assistant.isBlank()) {
            return response;
        }
        List<TaisceClient.Message> turn = List.of(new TaisceClient.Message("user", user), new TaisceClient.Message("assistant", assistant));
        try {
            client.observe(Memory.turnKey(dataSubjectId, runId, turn), dataSubjectId,
                    DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)), turn);
        } catch (RuntimeException e) {
            report("observe", e);
            throw e;
        }
        return response;
    }

    /** The key under which {@link #before} leaves the person's message for {@link #after}. */
    static final String USER_TEXT = "taisce.user_text";

    private static String lastUserText(Map<String, Object> context) {
        Object text = context.get(USER_TEXT);
        return text == null ? null : text.toString();
    }

    private static String assistantText(ChatClientResponse response) {
        if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
            return null;
        }
        Generation generation = response.chatResponse().getResult();
        AssistantMessage output = generation.getOutput();
        return output == null || output.hasToolCalls() ? null : output.getText();
    }

    private static int lastUserIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.getMessageType() == MessageType.USER && !Memory.isMemoryText(m.getText()) && m.getText() != null && !m.getText().isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private void report(String stage, Exception e) {
        if (onError != null) {
            onError.accept(stage, e);
        }
    }

    /** What {@link #before} does with the person's message so {@link #after} can store it: the context carries it across. */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, org.springframework.ai.chat.client.advisor.api.CallAdvisorChain chain) {
        List<Message> messages = request.prompt().getInstructions();
        int at = lastUserIndex(messages);
        ChatClientRequest carried = at < 0 ? request : request.mutate().context(USER_TEXT, messages.get(at).getText()).build();
        return BaseAdvisor.super.adviseCall(carried, chain);
    }
}
