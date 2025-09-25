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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

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

        String baseUrl = ctx.getEnvironment().getProperty("rag.chroma.base-url", "http://localhost:8000");
        String collection = ctx.getEnvironment().getProperty("rag.chroma.collection", "maverick_docs");
        EmbeddingStore<TextSegment> store = ChromaEmbeddingStore.builder()
                .baseUrl(baseUrl)
                .collectionName(collection)
                .build();

        Path root = Paths.get("src/main/resources/docs");
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            System.out.println("Docs directory not found: " + root);
            ctx.close();
            return;
        }

        for (Path roleDir : Files.list(root).filter(Files::isDirectory).collect(Collectors.toList())) {
            String roleId = roleDir.getFileName().toString();
            List<Document> documents = FileSystemDocumentLoader.loadDocuments(roleDir.toString());
            if (documents == null || documents.isEmpty()) continue;

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
            ingestor.ingest(documents);
            System.out.println("Ingested role: " + roleId + ", files: " + documents.size());
        }

        ctx.close();
        System.out.println("RAG ingest completed. Collection: " + collection);
    }
}


