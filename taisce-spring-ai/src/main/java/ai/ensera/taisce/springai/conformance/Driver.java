// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.springai.conformance;

import ai.ensera.taisce.client.Memory;
import ai.ensera.taisce.client.TaisceClient;
import ai.ensera.taisce.springai.TaisceMemoryAdvisor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * The conformance driver: the advisor wrapped in the subprocess protocol the suite speaks. The chat
 * client is Spring AI's own with the advisor attached; the model is scripted: it records what it was
 * handed and answers the turn's reply, or fails when the turn says so. Tool execution happens inside
 * a real model call in Spring AI, so a scripted model answers the final text directly and the tool
 * calls a turn lists never reach the advisor, which is the framework's property and not the
 * adapter's.
 */
public final class Driver {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Driver() {
    }

    static final class ScriptedModel implements ChatModel {
        private final JsonNode turn;
        private final ObjectNode report;

        ScriptedModel(JsonNode turn, ObjectNode report) {
            this.turn = turn;
            this.report = report;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ArrayNode seen = report.putArray("model_messages");
            for (Message m : prompt.getInstructions()) {
                ObjectNode entry = seen.addObject();
                entry.put("role", role(m.getMessageType()));
                entry.put("content", m.getText() == null ? "" : m.getText());
                Object untrusted = m.getMetadata() == null ? null : m.getMetadata().get(Memory.UNTRUSTED);
                entry.put("untrusted", Boolean.TRUE.equals(untrusted));
            }
            if (turn.path("model_failure").asBoolean(false)) {
                throw new IllegalStateException("the model failed");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(turn.path("assistant").asText("")))));
        }

        private static String role(MessageType type) {
            return switch (type) {
                case USER -> "user";
                case ASSISTANT -> "assistant";
                case SYSTEM -> "system";
                case TOOL -> "tool";
            };
        }
    }

    static ObjectNode runTurn(JsonNode instruction) {
        ObjectNode report = JSON.createObjectNode();
        report.putArray("model_messages");
        report.put("fatal", false);
        report.put("observed", false);
        JsonNode turn = instruction.path("turn");
        String subject = instruction.path("data_subject_id").asText(null);
        TaisceClient client = new TaisceClient(instruction.path("api").asText(), instruction.path("token").asText(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), Duration.ofSeconds(10));
        // When the turn arms compaction, any history at all trips it.
        TaisceMemoryAdvisor advisor = new TaisceMemoryAdvisor(client, subject, instruction.path("case").asText(), Map.of(), (stage, e) -> {
            // Stderr is the driver's own: a swallowed recall failure is still worth a line for a person.
            System.err.println(instruction.path("case").asText() + ": " + stage + " failed: " + e);
            if ("observe".equals(stage)) {
                report.put("observed", true);
                report.put("store_error", e.getMessage());
            }
        }, 0, turn.path("compact").asBoolean(false) ? 1 : 0);
        // The framework's own chat memory holds the earlier turns the application kept, and its
        // memory advisor puts them in the prompt before this advisor sees it.
        String conversation = instruction.path("case").asText();
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(50).build();
        List<Message> history = new ArrayList<>();
        for (JsonNode m : turn.path("history")) {
            history.add("assistant".equals(m.path("role").asText()) ? new AssistantMessage(m.path("content").asText()) : new UserMessage(m.path("content").asText()));
        }
        if (!history.isEmpty()) {
            chatMemory.add(conversation, history);
        }
        ChatClient chat = ChatClient.builder(new ScriptedModel(turn, report))
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), advisor).build();
        List<Message> messages = new ArrayList<>();
        for (JsonNode s : turn.path("synthetic")) {
            messages.add("assistant".equals(s.path("role").asText()) ? new AssistantMessage(s.path("content").asText()) : new UserMessage(s.path("content").asText()));
        }
        messages.add(new UserMessage(turn.path("user").asText()));
        try {
            chat.prompt().messages(messages).advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversation)).call().content();
            if (!turn.path("assistant").asText("").isBlank()) {
                report.put("observed", true);
            }
        } catch (RuntimeException e) {
            report.put("fatal", !report.has("store_error"));
        }
        return report;
    }

    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            System.out.println(runTurn(JSON.readTree(line)).toString());
            System.out.flush();
        }
    }
}
