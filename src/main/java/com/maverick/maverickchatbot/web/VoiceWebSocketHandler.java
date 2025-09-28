package com.maverick.maverickchatbot.web;

import com.maverick.maverickchatbot.ai.asr.SpeechToTextService;
import com.maverick.maverickchatbot.ai.tts.TtsService;
import com.maverick.maverickchatbot.ai.rag.ConversationOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.multipart.MultipartFile;
import java.nio.ByteBuffer;
import com.maverick.maverickchatbot.ai.roles.RoleService;
import com.maverick.maverickchatbot.ai.roles.RoleConfig;

@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private final SpeechToTextService speechToTextService;
    private final TtsService ttsService;
    private final RoleService roleService;
    private final ConversationOrchestrator conversationOrchestrator;

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        log.info("/ws/voice connected: {}", session.getId());
    }

    private static String toJsonString(String s) { return JsonUtil.toJsonString(s); }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) throws Exception {
        // 简单协议：收到 "ping" 回复 "pong"
        String payload = message.getPayload();
        if ("ping".equalsIgnoreCase(payload)) {
            session.sendMessage(new TextMessage("pong"));
        }
    }

    @Override
    protected void handleBinaryMessage(@NonNull WebSocketSession session, @NonNull BinaryMessage message) throws Exception {
        // 收到整段音频（二进制 WAV/AIFF 等），执行 ASR→LLM→TTS，返回音频（MP3 或 WAV）
        try {
            byte[] audioBytes = extractAudioBytes(message);
            MultipartFile file = toMultipart(audioBytes);
            String asrText = transcribe(file); // ASR
            log.info("用户语音输入：{}", asrText);

            // 过滤纯标点/空白或口头禅等噪声，避免发送无意义文本
            if (isNoisyText(asrText)) {
                log.info("忽略噪声/无效 ASR：{}", asrText);
                return;
            }

            // 简易去重：相同 ASR 在短时间内（2s）不重复处理
            try {
                var attrs = session.getAttributes();
                String lastProcessed = attrs != null ? (String) attrs.get("lastProcessedText") : null;
                Long lastAt = attrs != null ? (Long) attrs.get("lastProcessedAt") : null;
                long now = System.currentTimeMillis();
                if (asrText != null && !asrText.isEmpty() && lastProcessed != null && asrText.equals(lastProcessed) && lastAt != null && (now - lastAt) < 2000) {
                    log.info("忽略重复 ASR（2s 内相同文本）：{}", asrText);
                    return;
                }
                if (attrs != null) {
                    attrs.put("lastProcessedText", asrText);
                    attrs.put("lastProcessedAt", now);
                }
            } catch (Exception ignore) {}

            String roleId = extractRoleIdFromQuery(session);
            RoleConfig role = roleService.getById(roleId);

            String lastQuery = getSessionAttr(session, "lastQuery");
            String topicSummary = getSessionAttr(session, "topicSummary");
            String memorySummary = getSessionAttr(session, "memorySummary");
            String lastEscalatedRoleId = getSessionAttr(session, "lastEscalatedRoleId");

            var result = conversationOrchestrator.handleTurn(asrText, role, lastQuery, topicSummary, memorySummary, lastEscalatedRoleId);

            if (hasText(result.getTransferText())) {
                sendTextJson(session, asrText, result.getTransferText(), null);
                sendTts(session, result.getTransferText());
            }

            sendTextJson(session, asrText, result.getFinalText(), result.getAiRoleId());
            sendTts(session, result.getFinalText());

            updateSessionContext(session, result);

        } catch (Exception e) {
            log.error("WS handleBinaryMessage failed", e);
            // 不关闭连接，返回文本错误，前端可忽略或提示
            try { session.sendMessage(new TextMessage("error: " + e.getMessage())); } catch (Exception ignored) {}
        }
    }

    // ======== Private helpers (inside handler class) ========
    private byte[] extractAudioBytes(BinaryMessage message) {
        ByteBuffer payload = message.getPayload();
        byte[] audioBytes = new byte[payload.remaining()];
        payload.get(audioBytes);
        return audioBytes;
    }

    private MultipartFile toMultipart(byte[] audioBytes) {
        return new ByteArrayMultipartFile("file", "client.wav", "audio/wav", audioBytes);
    }

    private String transcribe(MultipartFile file) throws Exception {
        return speechToTextService.transcribe(file);
    }

    private String extractRoleIdFromQuery(WebSocketSession session) {
        try {
            var uri = session.getUri();
            if (uri == null) return null;
            String params = uri.getQuery();
            if (params == null) return null;
            for (String p : params.split("&")) {
                String[] kv = p.split("=", 2);
                if (kv.length == 2 && "roleId".equals(kv[0])) {
                    return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getSessionAttr(WebSocketSession session, String key) {
        try {
            var attrs = session.getAttributes();
            return attrs != null ? (String) attrs.get(key) : null;
        } catch (Exception e) { return null; }
    }

    private void updateSessionContext(WebSocketSession session, com.maverick.maverickchatbot.ai.rag.ConversationOrchestrator.Result result) {
        try {
            var attrs = session.getAttributes();
            if (attrs != null) {
                attrs.put("lastQuery", result.getNewLastQuery());
                // 取消对 topicSummary 的维护
                if (result.getNewMemorySummary() != null && !result.getNewMemorySummary().isEmpty()) {
                    attrs.put("memorySummary", result.getNewMemorySummary());
                }
                if (result.getNewEscalatedRoleId() != null) {
                    attrs.put("lastEscalatedRoleId", result.getNewEscalatedRoleId());
                }
            }
        } catch (Exception ignored) {}
    }

    private void sendTextJson(WebSocketSession session, String userText, String aiText, String aiRoleId) throws Exception {
        String json = "{\"type\":\"text\",\"user\":" + toJsonString(userText) + ",\"ai\":" + toJsonString(aiText) +
                (aiRoleId != null ? ",\"aiRoleId\":" + toJsonString(aiRoleId) : "") + "}";
        session.sendMessage(new TextMessage(json));
    }

    private void sendTts(WebSocketSession session, String text) throws Exception {
        String roleId = extractRoleIdFromQuery(session);
        String voice = null;
        try {
            RoleConfig rc = roleService.getById(roleId);
            if (rc != null && rc.getVoiceSamples() != null && !rc.getVoiceSamples().isEmpty()) {
                RoleConfig.VoiceSample first = rc.getVoiceSamples().get(0);
                if (first != null && first.getSpkId() != null && !first.getSpkId().isEmpty()) {
                    voice = first.getSpkId();
                }
            }
            // 若当前角色未配置 voiceSamples，则由 TtsService 内部回退到全局默认 voice-type
        } catch (Exception ignored) {}
        
        // 使用一次性合成，获取完整音频后发送给前端
        log.info("Starting TTS synthesis for text: '{}' with voice: '{}'", text, voice);
        byte[] audio = ttsService.synthesize(text, voice);
        if (audio != null && audio.length > 0) {
            log.info("TTS synthesis completed, sending audio ({} bytes) to frontend", audio.length);
            session.sendMessage(new BinaryMessage(audio));
        } else {
            log.warn("TTS synthesis returned empty audio for text: '{}'", text);
        }
    }

    private boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }

    // 判断是否为噪声或无效文本：纯标点/空白、或常见口头禅且过短
    private boolean isNoisyText(String s) {
        if (s == null) return true;
        String t = s.trim();
        if (t.isEmpty()) return true;
        // 去掉标点和空白后是否为空
        String noPunc = t.replaceAll("[\\p{P}‘’“”\u3000\s]+", "");
        if (noPunc.isEmpty()) return true;
        // 简单口头禅过滤：短且只包含以下词
        if (t.length() <= 2) {
            String[] fillers = {"啊","哦","呃","额","哈","嘿","呀","哎"};
            for (String f : fillers) {
                if (t.equals(f) || t.startsWith(f)) return true;
            }
        }
        return false;
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
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
    public @NonNull String getName() { return name; }

    @Override
    public String getOriginalFilename() { return originalFilename; }

    @Override
    public String getContentType() { return contentType; }

    @Override
    public boolean isEmpty() { return content == null || content.length == 0; }

    @Override
    public long getSize() { return content.length; }

    @Override
    public @NonNull byte[] getBytes() { return content; }

    @Override
    public @NonNull java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(content); }

    @Override
    public void transferTo(@NonNull java.io.File dest) throws java.io.IOException, java.lang.IllegalStateException {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
            fos.write(content);
        }
    }
}


