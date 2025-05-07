package com.abharan.javallm.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DisconnectedClientHelper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@CrossOrigin
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);
    private final Map<String, ChatMemory> chatMemories = new ConcurrentHashMap<>();

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Value("${google.search.api.key}")
    private String googleApiKey;

    @Value("${google.search.engine.id}")
    private String googleSearchEngineId;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DisconnectedClientHelper clientHelper =
            new DisconnectedClientHelper("com.abharan.javallm.controller.ChatController");

    @GetMapping(value = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(
            @RequestParam("message") String message,
            @RequestParam(value = "userId", defaultValue = "default") String userId) {
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder accumulator = new StringBuilder();

        ChatMemory memory = chatMemories.computeIfAbsent(userId, id ->
                MessageWindowChatMemory.builder().id(id).maxMessages(10).build());
        if (memory.messages().isEmpty()) {
            memory.add(new SystemMessage("You are a helpful AI assistant created by Abharan. " +
                    "Answer questions accurately and concisely."));
        }
        memory.add(UserMessage.from(message));

        List<ChatMessage> messages = new ArrayList<>(memory.messages());
        ChatRequest request = ChatRequest.builder().messages(messages).build();

        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String chunk) {
                String formatted = chunk.replace("\n", "  \n");
                sink.tryEmitNext(ServerSentEvent.builder(formatted).event("token").build());
                accumulator.append(chunk);
            }

            @Override
            public void onCompleteResponse(ChatResponse complete) {
                String response = accumulator.toString().replace("\n", "  \n");
                memory.add(AiMessage.from(accumulator.toString()));
                sink.tryEmitNext(ServerSentEvent.builder(response).event("complete").build());
                sink.tryEmitComplete();
            }

            @Override
            public void onError(Throwable e) {
                logger.error("Error in chat stream", e);
                if (clientHelper.checkAndLogClientDisconnectedException(e)) {
                    sink.tryEmitComplete();
                } else {
                    sink.tryEmitError(e);
                }
            }
        });

        return sink.asFlux()
                .timeout(Duration.ofMinutes(5))
                .onErrorResume(e -> {
                    if (clientHelper.checkAndLogClientDisconnectedException(e)) {
                        logger.debug("Client disconnected during chat stream");
                        return Flux.empty();
                    }
                    logger.error("Error in chat flux processing", e);
                    return Flux.error(e);
                });
    }

    @GetMapping(value = "/web-search", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> webSearch(
            @RequestParam("query") String query,
            @RequestParam(value = "userId", defaultValue = "default") String userId) {

        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder accumulator = new StringBuilder();

        ChatMemory memory = chatMemories.computeIfAbsent(userId, id ->
                MessageWindowChatMemory.builder().id(id).maxMessages(10).build());

        String searchResults = performWebSearch(query);
        memory.add(new SystemMessage("Web search results for \"" + query + "\":\n\n" + searchResults));
        memory.add(UserMessage.from("Please summarize these search results for: " + query));

        List<ChatMessage> messages = new ArrayList<>(memory.messages());
        ChatRequest request = ChatRequest.builder().messages(messages).build();

        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String chunk) {
                accumulator.append(chunk);
                sink.tryEmitNext(ServerSentEvent.builder(chunk).event("token").build());
            }

            @Override
            public void onCompleteResponse(ChatResponse complete) {
                memory.add(AiMessage.from(accumulator.toString()));
                sink.tryEmitNext(ServerSentEvent.builder(accumulator.toString())
                        .event("complete")
                        .build());
                sink.tryEmitComplete();
            }

            @Override
            public void onError(Throwable e) {
                logger.error("Error in web search stream", e);
                if (clientHelper.checkAndLogClientDisconnectedException(e)) {
                    sink.tryEmitComplete();
                } else {
                    sink.tryEmitError(e);
                }
            }
        });

        return sink.asFlux()
                .timeout(Duration.ofMinutes(5))
                .onErrorResume(e -> {
                    if (clientHelper.checkAndLogClientDisconnectedException(e)) {
                        logger.debug("Client disconnected during web search");
                        return Flux.empty();
                    }
                    logger.error("Error in web-search flux processing", e);
                    return Flux.error(e);
                });
    }

    private String performWebSearch(String query) {
        logger.info("Performing web search for query: {}", query);
        HttpURLConnection connection = null;
        BufferedReader br = null;
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            URL url = new URL("https://www.googleapis.com/customsearch/v1?key=" +
                    googleApiKey + "&cx=" + googleSearchEngineId +
                    "&q=" + encoded);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            if (connection.getResponseCode() != 200) {
                throw new RuntimeException("HTTP error code: " + connection.getResponseCode());
            }
            br = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder resp = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                resp.append(line);
            }
            return formatSearchResults(resp.toString());
        } catch (IOException e) {
            logger.error("Error performing web search", e);
            return "Unable to perform web search: " + e.getMessage();
        } finally {
            try {
                if (br != null) br.close();
            } catch (IOException e) {
                logger.error("Error closing reader", e);
            }
            if (connection != null) connection.disconnect();
        }
    }

    private String formatSearchResults(String jsonResponse) {
        logger.info("Formatting search results from JSON response");
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            StringBuilder formatted = new StringBuilder("### Search Results\n\n");

            if (root.has("searchInformation")) {
                JsonNode info = root.get("searchInformation");
                // Use long to avoid overflow on large result counts
                long totalResults = info.has("totalResults")
                        ? Long.parseLong(info.get("totalResults").asText())
                        : 0L;
                double searchTime = info.has("searchTime")
                        ? info.get("searchTime").asDouble()
                        : 0.0;
                formatted.append(String.format(
                        "Found %d results (%.2f seconds)\n\n",
                        totalResults, searchTime));
            }

            if (root.has("items") && root.get("items").isArray()) {
                JsonNode items = root.get("items");
                int limit = Math.min(items.size(), 5);
                for (int i = 0; i < limit; i++) {
                    JsonNode item = items.get(i);
                    String title = item.path("title").asText("No title");
                    String link = item.path("link").asText("No link");
                    String snippet = item.path("snippet").asText("No description");
                    String displayLink = item.path("displayLink").asText("");

                    formatted.append(String.format("%d. **%s**\n", i + 1, title));
                    formatted.append(String.format("   Source: %s\n", displayLink));
                    formatted.append(String.format("   URL: %s\n", link));
                    formatted.append(String.format("   %s\n\n", snippet));

                    JsonNode pagemap = item.path("pagemap").path("cse_image");
                    if (pagemap.isArray() && pagemap.size() > 0) {
                        String img = pagemap.get(0).path("src").asText();
                        formatted.append(String.format("   Image: %s\n\n", img));
                    }
                }
            } else {
                formatted.append("No results found for this query.");
            }
            return formatted.toString();
        } catch (IOException e) {
            logger.error("Error parsing JSON response", e);
            return "Error parsing search results: " + e.getMessage();
        }
    }
}
