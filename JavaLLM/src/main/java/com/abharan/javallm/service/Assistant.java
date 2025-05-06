package com.abharan.javallm.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface Assistant {

    @SystemMessage("You are a helpful and polite assistant.")
    String chat(String userMessage);
}
