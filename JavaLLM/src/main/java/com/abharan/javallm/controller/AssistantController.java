package com.abharan.javallm.controller;

import com.abharan.javallm.service.Assistant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AssistantController {

    private final Assistant assistant;

    public AssistantController(Assistant assistant) {
        this.assistant = assistant;
    }

    @GetMapping("/assistant")
    public String chat(@RequestParam(value = "message", defaultValue = "Hello") String message) {
        return assistant.chat(message);
    }
}
