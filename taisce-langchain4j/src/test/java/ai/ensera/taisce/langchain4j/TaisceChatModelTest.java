// Copyright 2026 The Taisce Authors
// SPDX-License-Identifier: Apache-2.0
package ai.ensera.taisce.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.ensera.taisce.client.Memory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What holds without a deployment: which message of a request is the person's. The rest is held by the conformance suite. */
class TaisceChatModelTest {
    @Test
    void thePersonsLastMessageIsFoundPastToolTrafficAndPastAnInjectedMemory() {
        UserMessage memory = UserMessage.builder().name("taisce").contents(List.of(TextContent.from(Memory.MESSAGE_PREFIX + "\n{}"))).build();
        List<ChatMessage> messages = List.of(
                UserMessage.from("earlier"),
                AiMessage.from("earlier answer"),
                memory,
                UserMessage.from("Book the room."),
                AiMessage.from(dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("c1").name("act").arguments("{}").build()),
                ToolExecutionResultMessage.from("c1", "act", "booked"));
        assertEquals(3, TaisceChatModel.lastUserIndex(messages));
        assertEquals(-1, TaisceChatModel.lastUserIndex(List.of(memory, AiMessage.from("x"))));
        assertEquals("", TaisceChatModel.text(UserMessage.from(List.of(TextContent.from("a"), TextContent.from("b")))));
    }

    @Test
    void compactionNeedsASubjectAndANonNegativeTrigger() {
        ai.ensera.taisce.client.TaisceClient client = new ai.ensera.taisce.client.TaisceClient("http://127.0.0.1:1", "tsk");
        dev.langchain4j.model.chat.ChatModel delegate = new dev.langchain4j.model.chat.ChatModel() {
            @Override
            public dev.langchain4j.model.chat.response.ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request) {
                return null;
            }
        };
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TaisceChatModel(delegate, client, null, "run", java.util.Map.of(), null, 8));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TaisceChatModel(delegate, client, "s", "run", java.util.Map.of(), null, -1));
        org.junit.jupiter.api.Assertions.assertNotNull(new TaisceChatModel(delegate, client, "s", "run", java.util.Map.of(), null, 8));
    }
}
