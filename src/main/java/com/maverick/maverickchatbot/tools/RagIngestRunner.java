package com.maverick.maverickchatbot.tools;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import com.maverick.maverickchatbot.ai.roles.RoleConfig;
import com.maverick.maverickchatbot.ai.roles.RoleService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 独立入库程序：遍历 docs/<roleId>/*.txt，切分，写入 Chroma（持久化）。
 * 运行示例：
 * mvn -q -DskipTests exec:java -Dexec.mainClass=com.maverick.maverickchatbot.tools.RagIngestRunner
 */
public class RagIngestRunner {

    public static void main(String[] args) throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder()
                .sources(com.maverick.maverickchatbot.MaverickChatbotApplication.class)
                .web(WebApplicationType.NONE)
                // 同时启用 ingest 与 local，确保沿用与主应用一致的 DashScope embedding（text-embedding-v4）
                .profiles("ingest", "local")
                .run(args);

        EmbeddingModel embeddingModel = ctx.getBean(EmbeddingModel.class);
        RoleService roleService = ctx.getBean(RoleService.class);

        String baseUrl = ctx.getEnvironment().getProperty("rag.chroma.base-url", "http://localhost:8000");
        String collection = ctx.getEnvironment().getProperty("rag.chroma.collection", "maverick_docs");
        EmbeddingStore<TextSegment> store = ChromaEmbeddingStore.builder()
                .baseUrl(baseUrl)
                .collectionName(collection)
                .build();

        long allStartNs = System.nanoTime();
        System.out.println("[Ingest] Start. collection=" + collection + ", baseUrl=" + baseUrl);

        Path root = Paths.get("src/main/resources/docs");
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            System.out.println("Docs directory not found: " + root);
            ctx.close();
            return;
        }

        // 遍历 roles.yaml：对每个角色按 docsDir（缺省回退 id）导入，role_id 使用角色 id
        for (RoleConfig role : roleService.listRoles()) {
            String roleId = role.getId();
            String dirName = (role.getDocsDir() != null && !role.getDocsDir().isEmpty()) ? role.getDocsDir() : roleId;
            Path roleDir = root.resolve(dirName);
            if (!Files.exists(roleDir) || !Files.isDirectory(roleDir)) {
                System.out.println("Skip role (docs dir not found): role=" + roleId + ", dir=" + roleDir);
                continue;
            }

            List<Document> documents = FileSystemDocumentLoader.loadDocuments(roleDir.toString());
            if (documents == null || documents.isEmpty()) {
                System.out.println("Empty docs, skip: role=" + roleId + ", dir=" + dirName);
                continue;
            }
            System.out.println("[Ingest] Role=" + roleId + ", dir=" + dirName + ", files=" + documents.size());
            long roleStartNs = System.nanoTime();

            DocumentByParagraphSplitter splitter = new DocumentByParagraphSplitter(1000, 200);
            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .documentSplitter(splitter)
                    .textSegmentTransformer(textSegment -> {
                        String fileName = textSegment.metadata().getString("file_name");
                        var md = textSegment.metadata();
                        md.put("role_id", roleId);
                        String text = (fileName != null ? fileName + "\n" : "") + textSegment.text();
                        return TextSegment.from(text, md);
                    })
                    .embeddingModel(embeddingModel)
                    .embeddingStore(store)
                    .build();
            // 逐文件入库并打印进度
            int total = documents.size();
            for (int i = 0; i < total; i++) {
                Document doc = documents.get(i);
                String fileName = null;
                try { fileName = doc.metadata() != null ? doc.metadata().getString("file_name") : null; } catch (Exception ignore) {}
                int charLen = doc.text() != null ? doc.text().length() : -1;
                System.out.println("[Ingest] (" + (i + 1) + "/" + total + ") start: role=" + roleId + ", file=" + (fileName == null ? "<unknown>" : fileName) + ", chars=" + charLen);
                long fileStartNs = System.nanoTime();
                ingestor.ingest(doc);
                long fileElapsedMs = (System.nanoTime() - fileStartNs) / 1_000_000;
                System.out.println("[Ingest] (" + (i + 1) + "/" + total + ") done : role=" + roleId + ", file=" + (fileName == null ? "<unknown>" : fileName) + ", timeMs=" + fileElapsedMs);
            }
            long roleElapsedMs = (System.nanoTime() - roleStartNs) / 1_000_000;
            System.out.println("[Ingest] Role done: role=" + roleId + ", files=" + documents.size() + ", timeMs=" + roleElapsedMs);
        }

        long allElapsedMs = (System.nanoTime() - allStartNs) / 1_000_000;
        System.out.println("[Ingest] All done. collection=" + collection + ", timeMs=" + allElapsedMs);

        ctx.close();
        System.out.println("RAG ingest completed. Collection: " + collection);
    }
}


