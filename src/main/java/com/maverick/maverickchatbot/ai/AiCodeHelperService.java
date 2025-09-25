package com.maverick.maverickchatbot.ai;

import dev.langchain4j.service.SystemMessage;

//@AiService
public interface AiCodeHelperService {

    @SystemMessage(fromResource = "system-prompt.txt")
    String chat(String userMessage);

}
