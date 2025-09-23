package com.maverick.maverickchatbot.ai;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.Result;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiCodeHelperServiceTest {

    @Resource
    private AiCodeHelperService aiCodeHelperService;

    @Test
    void chat() {
        String res = aiCodeHelperService.chat("你好，我是技术小牛");
        System.out.println(res);
    }

    @Test
    void chatWithMemory() {
        String res = aiCodeHelperService.chat("你好，我是技术小牛");
        System.out.println(res);
        String chat = aiCodeHelperService.chat("我是谁来着？");
        System.out.println(chat);
    }

    @Test
    void chatForReport() {
        String userMessage = "你好，我是技术小牛，帮我制定报告";
        AiCodeHelperService.Report report = aiCodeHelperService.chatForReport(userMessage);
        System.out.println(report);
    }

    @Test
    void chatWithRag() {
        Result<String> result = aiCodeHelperService.chatWithRag("怎么学习 Java？有哪些常见面试题？");
        String content = result.content();
        List<Content> sources = result.sources();
        System.out.println(content);
        System.out.println(sources);
    }
}