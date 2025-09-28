package com.maverick.maverickchatbot.ai.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RagSearchService {

    @Resource
    private EmbeddingModel qwenEmbeddingModel;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    public List<TextSegment> searchByRole(String query, String roleId, int maxResults, double minScore) {
        Embedding q = qwenEmbeddingModel.embed(normalize(query)).content();
        EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                .queryEmbedding(q)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
        var res = embeddingStore.search(req);

        List<TextSegment> out = new ArrayList<>();
        if (res == null || res.matches() == null) return out;
        for (EmbeddingMatch<TextSegment> m : res.matches()) {
            TextSegment seg = m.embedded();
            if (seg == null || seg.metadata() == null) continue;
            String rid = seg.metadata().getString("role_id");
            if (roleId == null || roleId.equals(rid)) {
                out.add(seg);
            }
        }
        return out;
    }

    /**
     * 在全库中检索，返回最相关的 role_id（取第一条匹配中携带的 role_id）。
     */
    public String findBestRoleId(String query, int maxResults, double minScore) {
        Embedding q = qwenEmbeddingModel.embed(normalize(query)).content();
        EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                .queryEmbedding(q)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
        var res = embeddingStore.search(req);
        if (res == null || res.matches() == null || res.matches().isEmpty()) return null;
        for (EmbeddingMatch<TextSegment> m : res.matches()) {
            TextSegment seg = m.embedded();
            if (seg == null || seg.metadata() == null) continue;
            String rid = seg.metadata().getString("role_id");
            if (rid != null && !rid.isEmpty()) return rid;
        }
        return null;
    }

    private String normalize(String s) {
        if (s == null) return "";
        // 去除常见标点/空白，统一空格，降低噪音（不做小写化，中文无影响）
        String t = s.replaceAll("[\\u3000\\s]+", " ")
                .replaceAll("[。，“”,、！？!?,.；;:：()（）\\-]+", " ")
                .trim();
        return t.isEmpty() ? s : t;
    }
}


