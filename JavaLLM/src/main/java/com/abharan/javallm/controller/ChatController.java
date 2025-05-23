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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.DisconnectedClientHelper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@CrossOrigin
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);
    private final Map<String, ChatMemory> chatMemories = new ConcurrentHashMap<>();
    private final Map<String, List<ConversationEntity>> userConversations = new ConcurrentHashMap<>();
    private final Map<String, UserProfile> userProfiles = new ConcurrentHashMap<>();

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Value("${google.search.api.key}")
    private String googleApiKey;

    @Value("${google.search.engine.id}")
    private String googleSearchEngineId;

    @Value("${file.upload.dir:./uploads}")
    private String uploadDir;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DisconnectedClientHelper clientHelper =
            new DisconnectedClientHelper("com.abharan.javallm.controller.ChatController");

    // Enhanced chat streaming with better error handling
    @GetMapping(value = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(
            @RequestParam("message") String message,
            @RequestParam(value = "userId", defaultValue = "default") String userId,
            @RequestParam(value = "conversationId", required = false) String conversationId) {

        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder accumulator = new StringBuilder();

        try {
            ChatMemory memory = getChatMemory(userId, conversationId);

            if (memory.messages().isEmpty()) {
                memory.add(new SystemMessage("You are Abby, a helpful AI assistant created by Abharan. " +
                        "You are knowledgeable, friendly, and provide accurate information. " +
                        "Format your responses using markdown when appropriate."));
            }

            memory.add(UserMessage.from(message));

            // Save message to conversation history
            saveMessageToConversation(userId, conversationId, "user", message);

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
                    String response = accumulator.toString();
                    memory.add(AiMessage.from(response));

                    // Save AI response to conversation history
                    saveMessageToConversation(userId, conversationId, "ai", response);

                    sink.tryEmitNext(ServerSentEvent.builder(response).event("complete").build());
                    sink.tryEmitComplete();
                }

                @Override
                public void onError(Throwable e) {
                    logger.error("Error in chat stream for user: {}", userId, e);
                    if (clientHelper.checkAndLogClientDisconnectedException(e)) {
                        sink.tryEmitComplete();
                    } else {
                        sink.tryEmitNext(ServerSentEvent.builder("Error: " + e.getMessage())
                                .event("error").build());
                        sink.tryEmitError(e);
                    }
                }
            });

        } catch (Exception e) {
            logger.error("Error initializing chat stream for user: {}", userId, e);
            sink.tryEmitNext(ServerSentEvent.builder("Error initializing chat: " + e.getMessage())
                    .event("error").build());
            sink.tryEmitComplete();
        }

        return sink.asFlux()
                .timeout(Duration.ofMinutes(10))
                .onErrorResume(e -> {
                    if (clientHelper.checkAndLogClientDisconnectedException(e)) {
                        logger.debug("Client disconnected during chat stream for user: {}", userId);
                        return Flux.empty();
                    }
                    logger.error("Error in chat flux processing for user: {}", userId, e);
                    return Flux.just(ServerSentEvent.builder("Connection error occurred")
                            .event("error").build());
                });
    }

    // Enhanced web search with better formatting
    @GetMapping(value = "/web-search", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> webSearch(
            @RequestParam("query") String query,
            @RequestParam(value = "userId", defaultValue = "default") String userId,
            @RequestParam(value = "conversationId", required = false) String conversationId) {

        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder accumulator = new StringBuilder();

        try {
            ChatMemory memory = getChatMemory(userId, conversationId);

            // Perform web search
            String searchResults = performWebSearch(query);

            // Save search query to conversation history
            saveMessageToConversation(userId, conversationId, "user", "[Search] " + query);

            String systemPrompt = String.format(
                    "You are Abby, an AI assistant with access to current web search results. " +
                            "Based on the following search results for the query '%s', provide a comprehensive, " +
                            "well-formatted response. Use markdown formatting and cite sources when possible.\n\n" +
                            "Search Results:\n%s", query, searchResults);

            memory.add(new SystemMessage(systemPrompt));
            memory.add(UserMessage.from("Please provide a comprehensive summary and analysis of these search results."));

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
                    String response = accumulator.toString();
                    memory.add(AiMessage.from(response));

                    // Save search response to conversation history
                    saveMessageToConversation(userId, conversationId, "ai", response);

                    sink.tryEmitNext(ServerSentEvent.builder(response).event("complete").build());
                    sink.tryEmitComplete();
                }

                @Override
                public void onError(Throwable e) {
                    logger.error("Error in web search stream for user: {}", userId, e);
                    if (clientHelper.checkAndLogClientDisconnectedException(e)) {
                        sink.tryEmitComplete();
                    } else {
                        sink.tryEmitNext(ServerSentEvent.builder("Search error: " + e.getMessage())
                                .event("error").build());
                        sink.tryEmitError(e);
                    }
                }
            });

        } catch (Exception e) {
            logger.error("Error initializing web search for user: {}", userId, e);
            sink.tryEmitNext(ServerSentEvent.builder("Error performing search: " + e.getMessage())
                    .event("error").build());
            sink.tryEmitComplete();
        }

        return sink.asFlux()
                .timeout(Duration.ofMinutes(10))
                .onErrorResume(e -> {
                    if (clientHelper.checkAndLogClientDisconnectedException(e)) {
                        logger.debug("Client disconnected during web search for user: {}", userId);
                        return Flux.empty();
                    }
                    logger.error("Error in web search flux processing for user: {}", userId, e);
                    return Flux.just(ServerSentEvent.builder("Search connection error occurred")
                            .event("error").build());
                });
    }

    // File upload endpoint
    @PostMapping(value = "/upload-file", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "message", defaultValue = "") String message,
            @RequestParam(value = "userId", defaultValue = "default") String userId,
            @RequestParam(value = "conversationId", required = false) String conversationId) {

        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder accumulator = new StringBuilder();

        try {
            // Create upload directory if it doesn't exist
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // Save uploaded file
            String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            Path filePath = uploadPath.resolve(fileName);
            file.transferTo(filePath.toFile());

            // Read file content
            String fileContent = readFileContent(filePath.toFile());

            ChatMemory memory = getChatMemory(userId, conversationId);

            String userMessage = String.format("File uploaded: %s\n%s\n\nFile content:\n%s",
                    file.getOriginalFilename(), message, fileContent);

            memory.add(UserMessage.from(userMessage));

            // Save message to conversation history
            saveMessageToConversation(userId, conversationId, "user",
                    String.format("[File: %s] %s", file.getOriginalFilename(), message));

            String systemPrompt = "You are Abby, an AI assistant. The user has uploaded a file. " +
                    "Analyze the file content and respond helpfully to any questions about it.";

            if (memory.messages().size() <= 1) {
                memory.add(new SystemMessage(systemPrompt));
            }

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
                    String response = accumulator.toString();
                    memory.add(AiMessage.from(response));

                    // Save AI response to conversation history
                    saveMessageToConversation(userId, conversationId, "ai", response);

                    sink.tryEmitNext(ServerSentEvent.builder(response).event("complete").build());
                    sink.tryEmitComplete();
                }

                @Override
                public void onError(Throwable e) {
                    logger.error("Error processing uploaded file for user: {}", userId, e);
                    sink.tryEmitNext(ServerSentEvent.builder("Error processing file: " + e.getMessage())
                            .event("error").build());
                    sink.tryEmitError(e);
                }
            });

        } catch (Exception e) {
            logger.error("Error handling file upload for user: {}", userId, e);
            sink.tryEmitNext(ServerSentEvent.builder("Error uploading file: " + e.getMessage())
                    .event("error").build());
            sink.tryEmitComplete();
        }

        return sink.asFlux()
                .timeout(Duration.ofMinutes(5))
                .onErrorResume(e -> {
                    logger.error("Error in file upload flux for user: {}", userId, e);
                    return Flux.just(ServerSentEvent.builder("File upload error occurred")
                            .event("error").build());
                });
    }

    // Conversation management endpoints
    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationEntity>> getConversations(
            @RequestParam(value = "userId", defaultValue = "default") String userId) {
        try {
            List<ConversationEntity> conversations = userConversations.getOrDefault(userId, new ArrayList<>());
            return ResponseEntity.ok(conversations);
        } catch (Exception e) {
            logger.error("Error retrieving conversations for user: {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/conversations")
    public ResponseEntity<ConversationEntity> createConversation(
            @RequestParam(value = "userId", defaultValue = "default") String userId,
            @RequestParam(value = "title", defaultValue = "New Conversation") String title) {
        try {
            ConversationEntity conversation = new ConversationEntity();
            conversation.setId(UUID.randomUUID().toString());
            conversation.setTitle(title);
            conversation.setUserId(userId);
            conversation.setCreatedAt(LocalDateTime.now());
            conversation.setUpdatedAt(LocalDateTime.now());
            conversation.setMessages(new ArrayList<>());

            userConversations.computeIfAbsent(userId, k -> new ArrayList<>()).add(conversation);

            return ResponseEntity.ok(conversation);
        } catch (Exception e) {
            logger.error("Error creating conversation for user: {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(
            @PathVariable String conversationId,
            @RequestParam(value = "userId", defaultValue = "default") String userId) {
        try {
            List<ConversationEntity> conversations = userConversations.get(userId);
            if (conversations != null) {
                conversations.removeIf(conv -> conv.getId().equals(conversationId));
                chatMemories.remove(userId + "_" + conversationId);
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("Error deleting conversation {} for user: {}", conversationId, userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PutMapping("/conversations/{conversationId}")
    public ResponseEntity<ConversationEntity> updateConversation(
            @PathVariable String conversationId,
            @RequestParam(value = "userId", defaultValue = "default") String userId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "archived", required = false) Boolean archived) {
        try {
            List<ConversationEntity> conversations = userConversations.get(userId);
            if (conversations != null) {
                for (ConversationEntity conv : conversations) {
                    if (conv.getId().equals(conversationId)) {
                        if (title != null) conv.setTitle(title);
                        if (archived != null) conv.setArchived(archived);
                        conv.setUpdatedAt(LocalDateTime.now());
                        return ResponseEntity.ok(conv);
                    }
                }
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error updating conversation {} for user: {}", conversationId, userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // User profile endpoints
    @GetMapping("/user-profile")
    public ResponseEntity<UserProfile> getUserProfile(
            @RequestParam(value = "userId", defaultValue = "default") String userId) {
        try {
            UserProfile profile = userProfiles.getOrDefault(userId, createDefaultProfile(userId));
            return ResponseEntity.ok(profile);
        } catch (Exception e) {
            logger.error("Error retrieving user profile for: {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/user-profile")
    public ResponseEntity<UserProfile> updateUserProfile(
            @RequestParam(value = "userId", defaultValue = "default") String userId,
            @RequestBody UserProfile profile) {
        try {
            profile.setUserId(userId);
            profile.setUpdatedAt(LocalDateTime.now());
            userProfiles.put(userId, profile);
            return ResponseEntity.ok(profile);
        } catch (Exception e) {
            logger.error("Error updating user profile for: {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // Export conversation
    @GetMapping("/conversations/{conversationId}/export")
    public ResponseEntity<String> exportConversation(
            @PathVariable String conversationId,
            @RequestParam(value = "userId", defaultValue = "default") String userId,
            @RequestParam(value = "format", defaultValue = "json") String format) {
        try {
            List<ConversationEntity> conversations = userConversations.get(userId);
            if (conversations != null) {
                Optional<ConversationEntity> conversation = conversations.stream()
                        .filter(conv -> conv.getId().equals(conversationId))
                        .findFirst();

                if (conversation.isPresent()) {
                    if ("json".equalsIgnoreCase(format)) {
                        String json = objectMapper.writeValueAsString(conversation.get());
                        return ResponseEntity.ok()
                                .header("Content-Disposition",
                                        "attachment; filename=\"conversation_" + conversationId + ".json\"")
                                .header("Content-Type", "application/json")
                                .body(json);
                    } else if ("txt".equalsIgnoreCase(format)) {
                        String text = formatConversationAsText(conversation.get());
                        return ResponseEntity.ok()
                                .header("Content-Disposition",
                                        "attachment; filename=\"conversation_" + conversationId + ".txt\"")
                                .header("Content-Type", "text/plain")
                                .body(text);
                    }
                }
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error exporting conversation {} for user: {}", conversationId, userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // Helper methods
    private ChatMemory getChatMemory(String userId, String conversationId) {
        String memoryKey = conversationId != null ? userId + "_" + conversationId : userId;
        return chatMemories.computeIfAbsent(memoryKey, id ->
                MessageWindowChatMemory.builder().id(id).maxMessages(20).build());
    }

    private void saveMessageToConversation(String userId, String conversationId, String sender, String content) {
        if (conversationId == null) return;

        List<ConversationEntity> conversations = userConversations.get(userId);
        if (conversations != null) {
            for (ConversationEntity conv : conversations) {
                if (conv.getId().equals(conversationId)) {
                    MessageEntity message = new MessageEntity();
                    message.setId(UUID.randomUUID().toString());
                    message.setSender(sender);
                    message.setContent(content);
                    message.setTimestamp(LocalDateTime.now());

                    conv.getMessages().add(message);
                    conv.setUpdatedAt(LocalDateTime.now());
                    break;
                }
            }
        }
    }

    private UserProfile createDefaultProfile(String userId) {
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setDisplayName("User");
        profile.setTheme("light");
        profile.setCreatedAt(LocalDateTime.now());
        profile.setUpdatedAt(LocalDateTime.now());
        return profile;
    }

    private String readFileContent(File file) throws IOException {
        String fileName = file.getName().toLowerCase();

        if (fileName.endsWith(".txt") || fileName.endsWith(".md") ||
                fileName.endsWith(".csv") || fileName.endsWith(".json")) {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } else if (fileName.endsWith(".pdf")) {
            // For PDF files, you would need a PDF library like Apache PDFBox
            return "PDF file content extraction not implemented yet.";
        } else if (fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
            // For Word documents, you would need Apache POI
            return "Word document content extraction not implemented yet.";
        } else {
            return "Unsupported file type for content extraction.";
        }
    }

    private String formatConversationAsText(ConversationEntity conversation) {
        StringBuilder sb = new StringBuilder();
        sb.append("Conversation: ").append(conversation.getTitle()).append("\n");
        sb.append("Created: ").append(conversation.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");

        for (MessageEntity message : conversation.getMessages()) {
            sb.append(message.getSender().toUpperCase()).append(" (")
                    .append(message.getTimestamp().format(DateTimeFormatter.ISO_LOCAL_TIME))
                    .append("): ").append(message.getContent()).append("\n\n");
        }

        return sb.toString();
    }

    private String performWebSearch(String query) {
        logger.info("Performing enhanced web search for query: {}", query);
        HttpURLConnection connection = null;
        BufferedReader br = null;
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            URL url = new URL("https://www.googleapis.com/customsearch/v1?key=" +
                    googleApiKey + "&cx=" + googleSearchEngineId +
                    "&q=" + encoded + "&num=8");

            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Abby-AI-Assistant/1.0");
            connection.setConnectTimeout(15_000);
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
        logger.info("Formatting enhanced search results from JSON response");
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            StringBuilder formatted = new StringBuilder();

            formatted.append("# 🔍 Search Results\n\n");

            if (root.has("searchInformation")) {
                JsonNode info = root.get("searchInformation");
                long totalResults = info.has("totalResults")
                        ? Long.parseLong(info.get("totalResults").asText())
                        : 0L;
                double searchTime = info.has("searchTime")
                        ? info.get("searchTime").asDouble()
                        : 0.0;
                formatted.append(String.format(
                        "**Found %,d results in %.2f seconds**\n\n",
                        totalResults, searchTime));
            }

            if (root.has("items") && root.get("items").isArray()) {
                JsonNode items = root.get("items");
                int limit = Math.min(items.size(), 8);

                for (int i = 0; i < limit; i++) {
                    JsonNode item = items.get(i);
                    String title = item.path("title").asText("No title");
                    String link = item.path("link").asText("No link");
                    String snippet = item.path("snippet").asText("No description");
                    String displayLink = item.path("displayLink").asText("");
                    String formattedDate = item.path("pagemap").path("metatags")
                            .path(0).path("article:published_time").asText("");

                    formatted.append(String.format("## %d. %s\n\n", i + 1, title));
                    formatted.append(String.format("**Source:** %s  \n", displayLink));
                    formatted.append(String.format("**URL:** [%s](%s)  \n", displayLink, link));
                    if (!formattedDate.isEmpty()) {
                        formatted.append(String.format("**Published:** %s  \n", formattedDate));
                    }
                    formatted.append(String.format("**Description:** %s\n\n", snippet));

                    // Add image if available
                    JsonNode pagemap = item.path("pagemap").path("cse_image");
                    if (pagemap.isArray() && pagemap.size() > 0) {
                        String imgUrl = pagemap.get(0).path("src").asText();
                        formatted.append(String.format("![Preview](%s)\n\n", imgUrl));
                    }

                    formatted.append("---\n\n");
                }
            } else {
                formatted.append("❌ **No results found for this query.**\n\n");
                formatted.append("Try rephrasing your search or using different keywords.");
            }

            return formatted.toString();
        } catch (IOException e) {
            logger.error("Error parsing JSON response", e);
            return "Error parsing search results: " + e.getMessage();
        }
    }

    // Entity classes
    public static class ConversationEntity {
        private String id;
        private String userId;
        private String title;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private boolean archived = false;
        private List<MessageEntity> messages = new ArrayList<>();

        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
        public boolean isArchived() { return archived; }
        public void setArchived(boolean archived) { this.archived = archived; }
        public List<MessageEntity> getMessages() { return messages; }
        public void setMessages(List<MessageEntity> messages) { this.messages = messages; }
    }

    public static class MessageEntity {
        private String id;
        private String sender;
        private String content;
        private LocalDateTime timestamp;
        private Map<String, Object> metadata = new HashMap<>();

        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getSender() { return sender; }
        public void setSender(String sender) { this.sender = sender; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }

    public static class UserProfile {
        private String userId;
        private String displayName;
        private String email;
        private String theme;
        private Map<String, Object> preferences = new HashMap<>();
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        // Getters and setters
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getTheme() { return theme; }
        public void setTheme(String theme) { this.theme = theme; }
        public Map<String, Object> getPreferences() { return preferences; }
        public void setPreferences(Map<String, Object> preferences) { this.preferences = preferences; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }
}
