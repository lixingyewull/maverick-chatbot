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
import dev.langchain4j.data.segment.TextSegment;
import java.util.List;
import com.maverick.maverickchatbot.ai.roles.RoleService;
import com.maverick.maverickchatbot.ai.roles.RoleConfig;
import com.maverick.maverickchatbot.ai.rag.RagSearchService;

@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private final AiCodeHelperService aiCodeHelperService;
    private final SpeechToTextService speechToTextService;
    private final TtsService ttsService;
    private final RoleService roleService;
    private final RagSearchService ragSearchService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("/ws/voice connected: {}", session.getId());
    }

    private static String toJsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int)c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
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
            log.info("用户语音输入：{}", asrText);
            String roleId = null;
            // 从ws的url获取角色id
            var params = session.getUri().getQuery();
            if (params != null) {
                for (String p : params.split("&")) {
                    String[] kv = p.split("=", 2);
                    if (kv.length == 2 && "roleId".equals(kv[0])) { roleId = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8); }
                }
            }
            RoleConfig role = roleService.getById(roleId);
            // 读取/维护会话上下文：lastQuery、topicSummary（不持久化跨角色）
            var attrs = session.getAttributes();
            String lastQuery = attrs != null ? (String) attrs.get("lastQuery") : null;
            String topicSummary = attrs != null ? (String) attrs.get("topicSummary") : null;

            // 本轮检索仅使用 URL 角色；不将跨角色结果持久化到会话
            String roleIdForSearch = role.getId();

            // 构造检索用 ragQuery：短句时拼接上文（lastQuery / topicSummary）
            String userQuery = asrText != null ? asrText.trim() : "";
            String ragQuery = userQuery;
            try {
                int codePoints = userQuery.codePointCount(0, userQuery.length());
                if (codePoints < 4) {
                    String ctx = (topicSummary != null && !topicSummary.isEmpty()) ? topicSummary
                            : (lastQuery != null ? lastQuery : "");
                    ragQuery = ctx.isEmpty() ? userQuery : (ctx + " " + userQuery);
                }
            } catch (Exception ignored) {}

            // 使用带元数据过滤的检索服务先取片段，拼接上下文后再走 LLM
            var segs = ragSearchService.searchByRole(ragQuery, roleIdForSearch, 5, 0.65);
            // 打印
            log.info("RAG search with role={} query='{}' segs count: {}", roleIdForSearch, ragQuery, (segs == null ? 0 : segs.size()));
            //打印结束
            // 如果当前角色的向量库中没搜到，找其他角色回答
            if (segs == null || segs.isEmpty()) {
                String bestRoleId = ragSearchService.findBestRoleId(asrText, 3, 0.75); // 找到最匹配的角色id
                if (bestRoleId == null) { // 无任何命中：让模型判断是寒暄还是需要知识
                    String stylePrefix = role.getPersonaPrompt() != null ? role.getPersonaPrompt() + "\n\n" : "";
                    String decidePrompt = stylePrefix +
                            "用户说：" + asrText + "\n" +
                            "请用当前角色口吻直接给出最终回复（最多2句）：\n" +
                            "- 若是寒暄/闲聊/不依赖外部知识的问题，请自然简短回应；\n" +
                            "- 若涉及事实/历史/专业且无可靠资料，请直接说‘我不清楚。’，不要编造，也不要解释理由。";
                    String aiText = aiCodeHelperService.chat(decidePrompt);

                    String json = "{\"type\":\"text\",\"user\":" + toJsonString(asrText) + ",\"ai\":" + toJsonString(aiText) + "}";
                    session.sendMessage(new TextMessage(json));
                    byte[] tts = ttsService.synthesize(aiText,"zh-CN-XiaoxiaoNeural");
                    session.sendMessage(new BinaryMessage(tts));
                    return;
                }
                segs = ragSearchService.searchByRole(asrText, bestRoleId, 3, 0.75); // 用最符合的角色再检索一次
                // 打印二次检索命中情况
                try {
                    log.info("Fallback role {} segs count: {}", bestRoleId, (segs == null ? 0 : segs.size()));
                    if (segs != null) {
                        int j = 0;
                        for (TextSegment seg2 : segs) {
                            if (j >= 3) break;
                            String rid2 = seg2.metadata() != null ? seg2.metadata().getString("role_id") : null;
                            String txt2 = seg2.text();
                            if (txt2 != null && txt2.length() > 200) txt2 = txt2.substring(0, 200);
                            log.info("fallback seg[{}] role_id={} text={}", j, rid2, txt2);
                            j++;
                        }
                    }
                } catch (Exception ignore) {}
                // 先由“当前角色”以其口吻生成一句过渡话
                String bestRoleName = roleService.getById(bestRoleId).getName();
                String stylePrefix = (role != null && role.getPersonaPrompt() != null) ? role.getPersonaPrompt() + "\n\n" : "";
                String transferPrompt = stylePrefix + "请用当前角色的口吻，简洁用中文说一句话：你不清楚该问题，但" + bestRoleName + " 更了解，你将请 TA 来回答。只输出这句话，勿添加多余解释。";
                String transferText = aiCodeHelperService.chat(transferPrompt);

                // 文本/语音提示仍以“当前角色”身份说出（不切换头像）
                String json = "{\"type\":\"text\",\"user\":" + toJsonString(asrText) + ",\"ai\":" + toJsonString(transferText) + "}";
                session.sendMessage(new TextMessage(json));
                byte[] tts = ttsService.synthesize(transferText,"zh-CN-XiaoxiaoNeural");
                session.sendMessage(new BinaryMessage(tts));
                roleIdForSearch = bestRoleId; // 仅本轮切换回答角色，不做会话持久化
            }

            StringBuilder ctx = new StringBuilder();
            for (int i = 0; i < segs.size(); i++) {
                ctx.append("[片段 ").append(i + 1).append("]\n").append(segs.get(i).text()).append("\n\n");
            }
            String prompt = ctx.append("问题：").append(asrText).toString();
            String aiText = aiCodeHelperService.chat(prompt);
            log.info("AI 输出：{}", aiText);

            // 先发送文本 JSON 供前端显示聊天气泡
            String json = "{\"type\":\"text\",\"user\":" + toJsonString(asrText) + ",\"ai\":" + toJsonString(aiText) + (roleIdForSearch!=null? ",\"aiRoleId\":"+toJsonString(roleIdForSearch):"") + "}";
            session.sendMessage(new TextMessage(json));
            byte[] tts = ttsService.synthesize(aiText,"zh-CN-XiaoxiaoNeural");
            session.sendMessage(new BinaryMessage(tts));

            // 更新 lastQuery / topicSummary（简易规则：lastQuery=本次问句；topicSummary 先复用最近2句）
            try {
                if (attrs != null) {
                    attrs.put("lastQuery", userQuery);
                    // 简易 topicSummary：若存在旧摘要则保留，否则用本次问句；可替换为模型摘要
                    if (topicSummary == null || topicSummary.isEmpty()) {
                        attrs.put("topicSummary", userQuery);
                    }
                }
            } catch (Exception ignored) {}

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


