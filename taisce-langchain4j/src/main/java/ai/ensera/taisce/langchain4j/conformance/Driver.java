// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.langchain4j.conformance;

import ai.ensera.taisce.client.TaisceClient;
import ai.ensera.taisce.client.Memory;
import ai.ensera.taisce.langchain4j.TaisceChatModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The conformance driver: the adapter wrapped in the subprocess protocol the suite speaks. The AI
 * service is LangChain4j's own with the memory attached and one tool; the model is scripted: it
 * records what it was handed on its first call and answers the turn's reply, calling the tool first
 * when the turn has tool calls, or fails when the turn says so.
 */
public final class Driver {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Driver() {
    }

    interface Assistant {
        String chat(String message);
    }

    /** The one tool: performs the call the turn scripted and answers its result. */
    public static final class Tools {
        private final Map<String, String> results;

        Tools(Map<String, String> results) {
            this.results = results;
        }

        @Tool("Performs the call the turn scripted and answers its result.")
        public String act(String call) {
            return results.getOrDefault(call, "");
        }
    }

    static final class ScriptedModel implements ChatModel {
        private final JsonNode turn;
        private final ObjectNode report;
        private int calls;

        ScriptedModel(JsonNode turn, ObjectNode report) {
            this.turn = turn;
            this.report = report;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            if (calls == 0) {
                ArrayNode seen = report.putArray("model_messages");
                for (ChatMessage m : request.messages()) {
                    ObjectNode entry = seen.addObject();
                    switch (m) {
                        case UserMessage u -> entry.put("role", "user").put("content", TaisceChatModel.text(u)).put("untrusted", Boolean.TRUE.equals(u.attribute(Memory.UNTRUSTED, Boolean.class)));
                        case AiMessage a -> entry.put("role", "assistant").put("content", a.text() == null ? "" : a.text());
                        case SystemMessage s -> entry.put("role", "system").put("content", s.text());
                        case ToolExecutionResultMessage t -> entry.put("role", "tool").put("content", t.text());
                        default -> entry.put("role", m.type().name().toLowerCase()).put("content", "");
                    }
                }
            }
            calls++;
            if (turn.path("model_failure").asBoolean(false)) {
                throw new IllegalStateException("the model failed");
            }
            JsonNode toolCalls = turn.path("tool_calls");
            if (calls <= toolCalls.size()) {
                JsonNode call = toolCalls.get(calls - 1);
                ToolExecutionRequest request1 = ToolExecutionRequest.builder().id("call-" + calls).name("act")
                        .arguments("{\"arg0\":" + JSON.valueToTree(call.path("call").asText()) + "}").build();
                return ChatResponse.builder().aiMessage(AiMessage.from(request1)).build();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(turn.path("assistant").asText(""))).build();
        }
    }

    static ObjectNode runTurn(JsonNode instruction) {
        ObjectNode report = JSON.createObjectNode();
        report.putArray("model_messages");
        report.put("fatal", false);
        report.put("observed", false);
        JsonNode turn = instruction.path("turn");
        Map<String, String> results = new HashMap<>();
        for (JsonNode call : turn.path("tool_calls")) {
            results.put(call.path("call").asText(), call.path("result").asText());
        }
        String subject = instruction.path("data_subject_id").asText(null);
        TaisceClient client = new TaisceClient(instruction.path("api").asText(), instruction.path("token").asText(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), Duration.ofSeconds(10));
        TaisceChatModel model = new TaisceChatModel(new ScriptedModel(turn, report), client, subject,
                instruction.path("case").asText(), Map.of(), (stage, e) -> {
                    // Stderr is the driver's own: a swallowed recall failure is still worth a line for a person.
                    System.err.println(instruction.path("case").asText() + ": " + stage + " failed: " + e);
                    if ("observe".equals(stage)) {
                        report.put("observed", true);
                        report.put("store_error", e.getMessage());
                    }
                }, turn.path("compact").asBoolean(false) ? 1 : 0);
        // The framework's own chat memory holds the earlier turns the application kept.
        MessageWindowChatMemory memory = MessageWindowChatMemory.withMaxMessages(50);
        for (JsonNode m : turn.path("history")) {
            memory.add("assistant".equals(m.path("role").asText()) ? AiMessage.from(m.path("content").asText()) : UserMessage.from(m.path("content").asText()));
        }
        for (JsonNode s : turn.path("synthetic")) {
            memory.add("assistant".equals(s.path("role").asText()) ? AiMessage.from(s.path("content").asText()) : UserMessage.from(s.path("content").asText()));
        }
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemory(memory)
                .tools(new Tools(results))
                .build();
        try {
            assistant.chat(turn.path("user").asText());
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
            ObjectNode report = runTurn(JSON.readTree(line));
            System.out.println(report.toString());
            System.out.flush();
        }
    }
}
