package com.maverick.maverickchatbot.web;

import com.maverick.maverickchatbot.ai.AiCodeHelperService;
import com.maverick.maverickchatbot.ai.asr.SpeechToTextService;
import com.maverick.maverickchatbot.ai.tts.TtsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.multipart.MultipartFile;
import java.nio.ByteBuffer;
import dev.langchain4j.service.Result;
import dev.langchain4j.rag.content.Content;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private final AiCodeHelperService aiCodeHelperService;
    private final SpeechToTextService speechToTextService;
    private final TtsService ttsService;

    @Value("${llm.max-output-chars:100}")
    private int maxOutputChars;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("/ws/voice connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 简单协议：收到 "ping" 回复 "pong"
        String payload = message.getPayload();
        if ("ping".equalsIgnoreCase(payload)) {
            session.sendMessage(new TextMessage("pong"));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        // 收到整段音频（二进制 WAV/AIFF 等），执行 ASR→LLM→TTS，返回音频（MP3 或 WAV）
        try {
            ByteBuffer payload = message.getPayload();
            byte[] audioBytes = new byte[payload.remaining()];
            payload.get(audioBytes);
            MultipartFile file = new ByteArrayMultipartFile("file", "client.wav", "audio/wav", audioBytes);
            String asrText = speechToTextService.transcribe(file);
            log.info("用户语音输入" + asrText);
            // 直接使用 AiCodeHelperService（内置 RAG）
            Result<String> result = aiCodeHelperService.chatWithRag(asrText);
            List<Content> sources = (result != null) ? result.sources() : null;
            if (sources == null || sources.isEmpty()) {
                log.info("RAG 无检索结果，跳过回复");
                try { session.sendMessage(new TextMessage("no_answer")); } catch (Exception ignored) {}
                return;
            }
            String aiText = result.content();
            if (aiText == null || aiText.isEmpty()) {
                try { session.sendMessage(new TextMessage("no_answer")); } catch (Exception ignored) {}
                return;
            }
            if (aiText.length() > maxOutputChars) {
                aiText = aiText.substring(0, Math.max(0, maxOutputChars));
            }
            log.info("AI 输出：" + aiText);
            byte[] tts = ttsService.synthesize(aiText,"zh-CN-XiaoxiaoNeural");
            session.sendMessage(new BinaryMessage(tts));
        } catch (Exception e) {
            log.error("WS handleBinaryMessage failed", e);
            // 不关闭连接，返回文本错误，前端可忽略或提示
            try { session.sendMessage(new TextMessage("error: " + e.getMessage())); } catch (Exception ignored) {}
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("/ws/voice closed: {} {}", session.getId(), status);
    }
}

class ByteArrayMultipartFile implements MultipartFile {
    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] content;

    ByteArrayMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.content = content;
    }

    @Override
    public String getName() { return name; }

    @Override
    public String getOriginalFilename() { return originalFilename; }

    @Override
    public String getContentType() { return contentType; }

    @Override
    public boolean isEmpty() { return content == null || content.length == 0; }

    @Override
    public long getSize() { return content.length; }

    @Override
    public byte[] getBytes() { return content; }

    @Override
    public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(content); }

    @Override
    public void transferTo(java.io.File dest) throws java.io.IOException, java.lang.IllegalStateException {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
            fos.write(content);
        }
    }
}


