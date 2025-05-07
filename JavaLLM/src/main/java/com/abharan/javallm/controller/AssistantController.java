package com.abharan.javallm.controller;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@CrossOrigin
public class AssistantController {

    @Autowired
    private ChatModel chatModel;

    // In-memory store for chat memories
    private final Map<String, ChatMemory> assistantMemories = new ConcurrentHashMap<>();

    @GetMapping("/assistant")
    public String chat(
            @RequestParam(value = "message", defaultValue = "Hello") String message,
            @RequestParam(value = "userId", required = false, defaultValue = "default") String userId) {

        // Get or create chat memory for this user
        ChatMemory memory = assistantMemories.computeIfAbsent(userId, id ->
                MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(10)
                        .build()
        );

        // Add user message to memory
        memory.add(UserMessage.from(message));

        // Get response from model using memory context
        ChatRequest request = ChatRequest.builder()
                .messages(memory.messages())
                .build();

        ChatResponse response = chatModel.chat(request);
        String responseText = response.aiMessage().text();
        memory.add(AiMessage.from(responseText));

        return responseText;
    }
}
