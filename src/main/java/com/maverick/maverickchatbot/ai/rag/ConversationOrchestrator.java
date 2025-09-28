package com.maverick.maverickchatbot.ai.rag;

import com.maverick.maverickchatbot.ai.roles.RoleConfig;
import com.maverick.maverickchatbot.ai.roles.RoleService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationOrchestrator {

    private final ChatModel qwenChatModel;
    private final RagSearchService ragSearchService;
    private final RoleService roleService;
    private String systemPromptTemplate;
    private String transferSystemPromptTemplate;

    @Value("${llm.debug.prompt:true}")
    private boolean debugPrompt;

    @Value("${llm.debug.max-log-len:-1}")
    private int debugMaxLogLen;

    @PostConstruct
    void loadSystemPromptTemplate() {
        this.systemPromptTemplate = readResource("system-prompt.txt");
        this.transferSystemPromptTemplate = readResource("transfer-system-prompt.txt");
    }

    public Result handleTurn(String asrText, RoleConfig role, String lastQuery, String topicSummary, String memorySummary, String lastEscalatedRoleId) {
        String userQuery = asrText != null ? asrText.trim() : "";
        String ragQuery = buildRagQuery(userQuery, lastQuery, memorySummary);
        log.info("RAG query built: memory='{}' | last='{}' | user='{}' => rag='{}'",
                memorySummary, lastQuery, userQuery, ragQuery);

        String roleIdForSearch = role.getId();
        List<TextSegment> segs = ragSearchService.searchByRole(ragQuery, roleIdForSearch, 5, 0.75);
        log.info("RAG search with role={} query='{}' segs count: {}", roleIdForSearch, ragQuery, (segs == null ? 0 : segs.size()));

        if (segs == null || segs.isEmpty()) {
            // 回退改用 ragQuery（已拼接上文），并放宽阈值，提升短问句召回
            String bestRoleId = ragSearchService.findBestRoleId(ragQuery, 3, 0.75);
            if (bestRoleId == null) {
                String decidePrompt =
                        "用户说：" + asrText + "\n" +
                        "请用当前角色口吻直接给出最终回复（最多2句）：\n" +
                        "- 若是寒暄/闲聊/不依赖外部知识的问题，请自然简短回应；\n" +
                        "- 若涉及事实/历史/专业且无可靠资料，请直接说‘我不清楚。’，不要编造，也不要解释理由。";
                String aiText = generateWithMessages(role, null, decidePrompt, memorySummary);
                return new Result(null, aiText, null, userQuery,
                        (topicSummary == null || topicSummary.isEmpty()) ? userQuery : topicSummary,
                        buildNewMemorySummary(memorySummary, userQuery, aiText),
                        null);
            }

            String bestRoleName = roleService.getById(bestRoleId).getName();
            boolean repeated = lastEscalatedRoleId != null && lastEscalatedRoleId.equals(bestRoleId);
            String transferSystem = getTransferSystemPrompt(role, bestRoleName);
            String transferPrompt = repeated
                    ? "生成一句过渡话：“我再去请" + bestRoleName + "确认一下。”要求自然口语、最多20字，可以根据规则润色。"
                    : "生成一句过渡话：“我不太清楚，请" + bestRoleName + "来回答。”要求自然口语、最多20字，可以根据规则润色。";
            String transferText = generateWithSystem(role, transferSystem, null, transferPrompt, memorySummary);

            List<TextSegment> segs2 = ragSearchService.searchByRole(ragQuery, bestRoleId, 3, 0.75);
            try {
                log.info("Fallback role {} segs count: {}", bestRoleId, (segs2 == null ? 0 : segs2.size()));
            } catch (Exception ignore) {}

            String ctx = buildContext(segs2);
            var answerRole = roleService.getById(bestRoleId);
            String finalText = generateWithMessages(answerRole, ctx, "问题：" + ragQuery, memorySummary);
            // 跨角色回答也写入会话记忆摘要（按用户要求启用）
            String updatedMemory = buildNewMemorySummary(memorySummary, userQuery, finalText);
            return new Result(transferText, finalText, bestRoleId, userQuery,
                    (topicSummary == null || topicSummary.isEmpty()) ? userQuery : topicSummary,
                    updatedMemory,
                    bestRoleId);
        }

        String ctx = buildContext(segs);
        String finalText = generateWithMessages(role, ctx, "问题：" + ragQuery, memorySummary);
        return new Result(null, finalText, roleIdForSearch, userQuery,
                (topicSummary == null || topicSummary.isEmpty()) ? userQuery : topicSummary,
                buildNewMemorySummary(memorySummary, userQuery, finalText),
                null);
    }

    private String buildRagQuery(String userQuery, String lastQuery, String memorySummary) {
        // 先尝试用 LLM 将本轮话语在上下文下改写为“自包含、明确”的检索问题
        try {
            String rewritten = rewriteQueryWithLlm(userQuery, lastQuery, memorySummary);
            if (rewritten != null && !rewritten.isEmpty()) {
                try { log.info("RAG query rewritten by LLM: '{}' => '{}'", userQuery, rewritten); } catch (Exception ignore) {}
                return rewritten;
            }
        } catch (Exception e) {
            try { log.warn("LLM 查询改写失败，回退到拼接策略: {}", e.getMessage()); } catch (Exception ignore) {}
        }

        // 回退策略：使用记忆摘要（尾部）或上一问 拼接 当前问
        String mem = memorySummary != null ? memorySummary.trim() : "";
        if (!mem.isEmpty()) {
            String memTail = tail(mem, 60);
            return memTail + " " + userQuery;
        }
        if (lastQuery != null && !lastQuery.isEmpty()) {
            return lastQuery + " " + userQuery;
        }
        return userQuery;
    }

    /**
     * 查询改写：结合记忆与上一轮问题，将本轮用户话语改写成自包含、明确的中文检索问题。
     * 约束：
     * - 只输出一行最终问题；解析指代/承接；必要时从记忆/上一轮补全省略信息
     * - 不得臆测未知事实；无法确定就保留原问；长度≤30字
     */
    private String rewriteQueryWithLlm(String userQuery, String lastQuery, String memorySummary) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        String system = "你是查询改写器。基于给定的上下文记忆与上一轮问题，将本轮用户话语改写为“自包含、明确、可用于知识检索”的中文问题。" +
                "要求：只输出改写后的一个问题；需要解析指代/承接/比较；必要时从记忆或上一轮补全主语或限定词；" +
                "若无法确定语义则保留原问，不要编造；不超过30字。";
        messages.add(new SystemMessage(system));

        StringBuilder block = new StringBuilder();
        block.append("<memory>\n").append(memorySummary == null ? "" : memorySummary).append("\n</memory>\n\n");
        block.append("<last_query>\n").append(lastQuery == null ? "" : lastQuery).append("\n</last_query>\n\n");
        block.append("<current_utterance>\n").append(userQuery == null ? "" : userQuery).append("\n</current_utterance>\n");
        messages.add(new UserMessage(block.toString()));

        if (debugPrompt) {
            try { log.info("RAG Rewrite Request:\n{}", formatForLog(messages)); } catch (Exception ignore) {}
        }

        var response = qwenChatModel.chat(messages);
        String text = response == null || response.aiMessage() == null ? null : response.aiMessage().text();
        if (text == null) return null;
        String out = text.trim();
        // 清理可能的引号、标点包裹
        if ((out.startsWith("\"") && out.endsWith("\"")) || (out.startsWith("“") && out.endsWith("”"))) {
            out = out.substring(1, out.length() - 1).trim();
        }
        return out;
    }

    private String tail(String s, int n) {
        if (s == null) return null;
        int len = s.length();
        if (n >= len) return s;
        return s.substring(len - n);
    }

    private String buildContext(List<TextSegment> segs) {
        if (segs == null || segs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.size(); i++) {
            sb.append("[片段 ").append(i + 1).append("]\n").append(segs.get(i).text()).append("\n\n");
        }
        return sb.toString();
    }

    private String generateWithMessages(RoleConfig role, String ctxOrNull, String userPrompt, String memorySummary) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        String systemPrompt = getSystemPrompt(role);
        messages.add(new SystemMessage(systemPrompt));

        // 组合式用户块：few-shot + retrieved_context + user_question
        StringBuilder block = new StringBuilder();
        String roleName = role != null ? role.getName() : "AI";
        // few-shot
        block.append("<few_shot_examples>\n");
        if (role != null && role.getExamples() != null && !role.getExamples().isEmpty()) {
            int k = 0;
            for (RoleConfig.Example ex : role.getExamples()) {
                if (ex == null) continue;
                String u = ex.getUser();
                String a = ex.getAi();
                if (u == null || u.isEmpty() || a == null || a.isEmpty()) continue;
                block.append("用户: ").append(u).append("\n");
                block.append(roleName).append(": ").append(a).append("\n\n");
                if (++k >= 5) break;
            }
        }
        block.append("</few_shot_examples>\n\n");

        // injected conversation memory
        if (memorySummary != null && !memorySummary.isEmpty()) {
            block.append("<conversation_memory>\n");
            block.append(memorySummary).append("\n");
            block.append("</conversation_memory>\n\n");
        }

        // retrieved context
        block.append("<retrieved_context>\n");
        if (ctxOrNull != null && !ctxOrNull.isEmpty()) {
            block.append(ctxOrNull).append("\n");
        }
        block.append("</retrieved_context>\n\n");

        // user question
        block.append("<user_question>\n");
        block.append(userPrompt).append("\n");
        block.append("</user_question>\n");

        messages.add(new UserMessage(block.toString()));

        if (debugPrompt) {
            try { log.info("LLM Request Messages:\n{}", formatForLog(messages)); } catch (Exception ignore) {}
        }

        var response = qwenChatModel.chat(messages);
        return response.aiMessage().text();
    }

    private String generateWithSystem(RoleConfig role, String systemText, String ctxOrNull, String userPrompt, String memorySummary) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemText));
        StringBuilder block = new StringBuilder();
        block.append("<user_request>\n").append(userPrompt).append("\n</user_request>\n");
        if (memorySummary != null && !memorySummary.isEmpty()) {
            block.append("<conversation_memory>\n").append(memorySummary).append("\n</conversation_memory>\n");
        }
        if (ctxOrNull != null && !ctxOrNull.isEmpty()) {
            block.append("<retrieved_context>\n").append(ctxOrNull).append("\n</retrieved_context>\n");
        }
        messages.add(new UserMessage(block.toString()));
        if (debugPrompt) {
            try { log.info("LLM System-Driven Request Messages:\n{}", formatForLog(messages)); } catch (Exception ignore) {}
        }
        var response = qwenChatModel.chat(messages);
        return response.aiMessage().text();
    }

    @NotNull
    private String getSystemPrompt(RoleConfig role) {
        String tpl = (systemPromptTemplate != null && !systemPromptTemplate.isEmpty())
                ? systemPromptTemplate
                : "I want you to act like {{character}} from {{series}}.\n" +
                  "You are now cosplay {{character}}.\n" +
                  "If others‘ questions are related with the novel, please try to reuse the original lines from the novel.\n" +
                  "I want you to respond and answer like {{character}} using the tone, manner and vocabulary {{character}} would use.\n" +
                  "You must know all of the knowledge of {{character}}.\n" +
                  "Do not output meta statements like ‘明白了/我会按照示例/你想问我什么/让我来/接下来’. Never acknowledge instructions or examples; just respond directly in character with the final answer.";
        String character = role != null ? role.getName() : "";
        String series = role != null && role.getSeries() != null ? role.getSeries() : "";
        String persona = role != null && role.getPersonaPrompt() != null ? role.getPersonaPrompt() : "";
        return tpl.replace("{{character}}", character)
                  .replace("{{series}}", series)
                  .replace("{{persona}}", persona);
    }

    private String getTransferSystemPrompt(RoleConfig role, String bestRoleName) {
        return transferSystemPromptTemplate.replace("{{character}}", role.getName())
                                            .replace("{{series}}", role.getSeries())
                                            .replace("{{persona}}", role.getPersonaPrompt())
                                            .replace("{{best_role_name}}", bestRoleName);
    }

    private String readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            byte[] bytes = in.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private String formatForLog(List<dev.langchain4j.data.message.ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            var m = messages.get(i);
            String role = (m instanceof SystemMessage) ? "system" : (m instanceof UserMessage) ? "user" : m.type().name();
            String text = m.toString();
            if (debugMaxLogLen > 0 && text != null && text.length() > debugMaxLogLen) {
                text = text.substring(0, debugMaxLogLen) + "...<truncated>";
            }
            sb.append("[").append(i).append("] ").append(role).append(":\n").append(text == null ? "" : text).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 使用 LLM 将旧摘要与本轮对话合并成新的会话记忆摘要。
     * 输出要求：
     * - 仅保留后续多轮有价值的信息（用户偏好、长任务目标、事实约束、命名实体、上下文锚点）
     * - 丢弃闲聊与一次性问题细节，避免冗长；禁止虚构
     * - 中文输出，<=200字，短句用分号分隔
     */
    private String summarizeMemoryWithLlm(String oldSummary, String userQuery, String aiText) {
        try {
            List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
            String system = "你是会话记忆提炼器，负责将已有摘要与最新一轮对话合并成更精炼、可复用的记忆。" +
                    "只保留会影响后续对话的关键信息（用户偏好、长期目标、事实约束、命名实体、上下文锚点）；" +
                    "删除寒暄与一次性细节；不得虚构；中文输出，不超过200字，短句用分号分隔。";
            messages.add(new SystemMessage(system));

            StringBuilder block = new StringBuilder();
            block.append("<existing_summary>\n").append(oldSummary == null ? "" : oldSummary).append("\n</existing_summary>\n\n");
            block.append("<new_turn>\n");
            block.append("用户: ").append(userQuery == null ? "" : userQuery).append("\n");
            block.append("AI: ").append(aiText == null ? "" : aiText).append("\n");
            block.append("</new_turn>\n");
            messages.add(new UserMessage(block.toString()));

            if (debugPrompt) {
                try { log.info("Memory Summarize Request:\n{}", formatForLog(messages)); } catch (Exception ignore) {}
            }

            var response = qwenChatModel.chat(messages);
            String text = response == null || response.aiMessage() == null ? null : response.aiMessage().text();
            return text == null ? null : text.trim();
        } catch (Exception e) {
            try { log.warn("summarizeMemoryWithLlm error: {}", e.getMessage()); } catch (Exception ignore) {}
            return null;
        }
    }

    @Data
    @AllArgsConstructor
    public static class Result {
        private String transferText;
        private String finalText;
        private String aiRoleId;
        private String newLastQuery;
        private String newTopicSummary;
        private String newMemorySummary;
        private String newEscalatedRoleId;
    }

    private String buildNewMemorySummary(String oldSummary, String userQuery, String aiText) {
        try {
            String summary = summarizeMemoryWithLlm(oldSummary, userQuery, aiText);
            if (summary != null && !summary.isEmpty()) {
                return summary;
            }
        } catch (Exception e) {
            try { log.warn("LLM 记忆总结失败，回退到简单拼接: {}", e.getMessage()); } catch (Exception ignore) {}
        }
        try {
            String base = (oldSummary == null || oldSummary.isEmpty()) ? "" : (oldSummary + " ");
            String clippedAi = aiText != null && aiText.length() > 40 ? aiText.substring(0, 40) : (aiText == null ? "" : aiText);
            return (base + "用户:" + userQuery + " | AI:" + clippedAi).trim();
        } catch (Exception e) { return oldSummary; }
    }
}


