package com.example.assistant.service;

import com.example.assistant.config.PromptConfig;
import com.example.assistant.model.ChatRequest;
import com.example.assistant.util.ChatRetryConfig;
import io.github.resilience4j.retry.Retry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

import java.util.Base64;

@Service
public class ChatService {
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final ChatMemoryService chatMemoryService;
    private final Retry chatRetry;

    public ChatService(ChatClient.Builder chatClientBuilder,
                      ChatMemoryService chatMemoryService,
                      VectorStore vectorStore,
                      DateTimeService dateTimeService,
                      ToolCallbackProvider tools,
                      Retry chatRetry) {
        this.chatClient = chatClientBuilder
                .defaultSystem(PromptConfig.SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemoryService.getChatMemory()).build(),
                        QuestionAnswerAdvisor.builder(vectorStore).build()
                )
                .defaultTools(dateTimeService)
                .defaultToolCallbacks(tools)
                .build();

        this.chatMemoryService = chatMemoryService;
        this.chatRetry = chatRetry;
        logger.info("ChatService initialized with embedded ChatClient");
    }

    public String processChat(ChatRequest request) {
        logger.info("Processing chat request - hasFile: {}, hasPrompt: {}, fileName: {}",
                request.hasFile(), request.hasPrompt(), request.fileName());
        try {
            if (!request.hasFile()) {
                return chatRetry.executeSupplier(() -> sendTextPrompt(request));
            } else {
                FileResource fileResource = buildFileResource(request);
                return chatRetry.executeSupplier(() -> sendFilePrompt(request, fileResource));
            }
        } catch (Exception e) {
            return handleException(e);
        }
    }

    @NotNull
    private FileResource buildFileResource(ChatRequest request) {
        MimeType mimeType;
        if (request.fileName() != null && !request.fileName().trim().isEmpty()) {
            MediaType mediaType = MediaTypeFactory.getMediaType(request.fileName())
                    .orElse(MediaType.APPLICATION_OCTET_STREAM);
            mimeType = new MimeType(mediaType.getType(), mediaType.getSubtype());
        } else {
            mimeType = MimeTypeUtils.APPLICATION_OCTET_STREAM;
        }
        byte[] fileData = Base64.getDecoder().decode(request.fileBase64());
        ByteArrayResource resource = new ByteArrayResource(fileData);
        return new FileResource(mimeType, resource);
    }

    @Nullable
    private String sendTextPrompt(ChatRequest request) {
        var chatResponse = chatClient
                .prompt().user(request.prompt())
                .advisors(chatMemoryService::addConversationIdToAdvisor)
                .call()
                .chatResponse();
        return (chatResponse != null) ? chatResponse.getResult().getOutput().getText() : "I don't know - no response received.";
    }

    @Nullable
    private String sendFilePrompt(ChatRequest request, FileResource fileResource) {
        // Determine prompt inline
        String actualPrompt;
        if (!request.hasPrompt()) {
            logger.info("Using document analysis prompt for file: {}", request.fileName());
            actualPrompt = PromptConfig.DOCUMENT_ANALYSIS_PROMPT;
        } else {
            logger.info("Using user prompt: '{}'", request.prompt());
            actualPrompt = request.prompt();
        }
        var chatResponse = chatClient
                .prompt()
                .user(userSpec -> {
                    userSpec.text(actualPrompt);
                    userSpec.media(fileResource.mimeType(), fileResource.resource());
                })
                .advisors(chatMemoryService::addConversationIdToAdvisor)
                .call().chatResponse();
        return (chatResponse != null) ? chatResponse.getResult().getOutput().getText() : "I don't know - no response received.";
    }

    private String handleException(Throwable throwable) {
        if (throwable instanceof ValidationException) {
            logger.warn("AWS Bedrock validation error: {}", throwable.getMessage());
            return "Invalid request format. Please check your input and try again.";
        } else if (ChatRetryConfig.isAwsThrottlingRelated(throwable)) {
            logger.error("Throttling exception after all retry attempts: {}", throwable.getMessage());
            return "The AI service is currently experiencing high demand. Please try again in a few minutes.";
        } else {
            logger.error("Error processing chat request", throwable);
            return "I don't know - there was an error processing your request.";
        }
    }

    private record FileResource(MimeType mimeType, ByteArrayResource resource) {
    }
}
