package com.example.assistant.controller;

import com.example.assistant.model.ChatRequest;
import com.example.assistant.service.ChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api")
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("chat")
    public String chat(@RequestBody ChatRequest request) {
        return chatService.processChat(request);
    }
}
