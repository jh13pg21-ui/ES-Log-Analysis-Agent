package cn.bugstack.ai.domain.agent.service.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 语义段落分块器
 * <p>
 * 策略：遇到 Markdown 标题（#）或连续空行时切块，
 * 单块超过 MAX_CHUNK_CHARS 时强制切割，保持语义完整。
 */
@Component
public class SemanticTextSplitter implements DocumentTransformer {

    private static final int MAX_CHUNK_CHARS = 800;
    private static final int MIN_CHUNK_CHARS = 50;

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            result.addAll(splitDocument(doc));
        }
        return result;
    }

    private List<Document> splitDocument(Document doc) {
        List<Document> chunks = new ArrayList<>();
        String[] paragraphs = doc.getText().split("\\n\\n+");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;

            boolean isHeading = trimmed.startsWith("#");

            // 遇到新标题且当前块非空：先保存当前块
            if (isHeading && current.length() >= MIN_CHUNK_CHARS) {
                addChunk(chunks, doc, current.toString().trim());
                current = new StringBuilder();
            }

            current.append(trimmed).append("\n\n");

            // 块过长时强制切割
            if (current.length() >= MAX_CHUNK_CHARS) {
                addChunk(chunks, doc, current.toString().trim());
                current = new StringBuilder();
            }
        }

        if (current.length() >= MIN_CHUNK_CHARS) {
            addChunk(chunks, doc, current.toString().trim());
        }

        return chunks;
    }

    private void addChunk(List<Document> chunks, Document source, String text) {
        chunks.add(new Document(text, new HashMap<>(source.getMetadata())));
    }

}
