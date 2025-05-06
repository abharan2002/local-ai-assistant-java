package com.abharan.javallm.controller;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.DisconnectedClientHelper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.time.Duration;

@RestController
@CrossOrigin
public class ChatController {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    private final DisconnectedClientHelper clientHelper = new DisconnectedClientHelper("com.abharan.javallm.controller.ChatController");

    @GetMapping(value = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(
            @RequestParam(value = "message") String message
    ) {
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder accumulator = new StringBuilder();

        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(message))
                .build();

        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String chunk) {
                accumulator.append(chunk);
                sink.tryEmitNext(ServerSentEvent.builder(chunk)
                        .event("token")
                        .build());
            }

            @Override
            public void onCompleteResponse(ChatResponse complete) {
                sink.tryEmitNext(ServerSentEvent.builder(accumulator.toString())
                        .event("complete")
                        .build());
                sink.tryEmitComplete();
            }

            @Override
            public void onError(Throwable e) {
                // Check if this is a client disconnect exception
                if (clientHelper.checkAndLogClientDisconnectedException(e)) {
                    // Just complete the sink without error for client disconnections
                    sink.tryEmitComplete();
                } else {
                    // For other errors, propagate them
                    sink.tryEmitError(e);
                }
            }
        });

        return sink.asFlux()
                .timeout(Duration.ofMinutes(5))
                .onErrorResume(e -> {
                    // Additional safety net for errors that happen during flux processing
                    if (clientHelper.checkAndLogClientDisconnectedException(e)) {
                        return Flux.empty(); // Return empty flux for client disconnections
                    }
                    return Flux.error(e); // Propagate other errors
                });
    }
}