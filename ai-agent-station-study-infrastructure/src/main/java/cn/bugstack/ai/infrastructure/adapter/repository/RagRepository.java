package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.agent.service.IRagService;
import cn.bugstack.ai.domain.agent.service.rag.PdfStructureDocumentReader;
import cn.bugstack.ai.domain.agent.service.rag.SemanticTextSplitter;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentDao;
import cn.bugstack.ai.infrastructure.dao.po.RagDocument;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 文档仓储实现
 * <p>
 * upload：文本提取 → 分块 → VectorStore.add（自动 Embedding）→ MySQL 记录
 * delete：PgVector 按 document_id 删除 → MySQL 删除
 */
@Slf4j
@Repository
public class RagRepository implements IRagService {

    @Resource
    private IRagDocumentDao ragDocumentDao;

    @Resource
    private VectorStore vectorStore;

    @Autowired(required = false)
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgVectorJdbcTemplate;

    @Resource
    private TokenTextSplitter tokenTextSplitter;

    @Resource
    private SemanticTextSplitter semanticTextSplitter;

    @Resource
    private PdfStructureDocumentReader pdfStructureDocumentReader;

    private static final String VECTOR_TABLE = "vector_store_openai";

    @Override
    public String uploadDocument(String agentId, String fileName, String fileType,
                                 byte[] content, String chunkStrategy) {
        String documentId = UUID.randomUUID().toString();

        // 1. MySQL 记录：processing 状态
        ragDocumentDao.insert(RagDocument.builder()
                .documentId(documentId)
                .agentId(agentId)
                .fileName(fileName)
                .fileType(fileType)
                .chunkStrategy(chunkStrategy)
                .chunkCount(0)
                .status("processing")
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build());

        try {
            // 2. 文本提取
            //    - PDF：用 PDFBox 按章节/页面切分，保留结构信息
            //    - 其他二进制格式：用 Tika 提取纯文本
            //    - 纯文本格式：直接字节转字符串
            String text;
            List<Document> structuredDocs = null;  // PDF 结构化结果，跳过后续分块
            if ("pdf".equals(fileType)) {
                Map<String, Object> pdfMeta = new HashMap<>();
                pdfMeta.put("agent_id", agentId);
                pdfMeta.put("document_id", documentId);
                pdfMeta.put("file_name", fileName);
                pdfMeta.put("file_type", fileType);
                pdfMeta.put("upload_time", LocalDateTime.now().toString());
                pdfMeta.put("source", "user_upload");

                structuredDocs = pdfStructureDocumentReader.read(content, pdfMeta);
                text = structuredDocs.stream().map(Document::getText).collect(Collectors.joining("\n"));
            } else if (isPlainText(fileType)) {
                text = new String(content, StandardCharsets.UTF_8);
            } else {
                TikaDocumentReader tikaReader = new TikaDocumentReader(new ByteArrayResource(content));
                List<Document> tikaDocs = tikaReader.get();
                text = tikaDocs.stream().map(Document::getText).collect(Collectors.joining("\n"));
            }

            // 3. 基础 Document + 元数据
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("agent_id", agentId);
            metadata.put("document_id", documentId);
            metadata.put("file_name", fileName);
            metadata.put("file_type", fileType);
            metadata.put("chunk_strategy", chunkStrategy);
            metadata.put("upload_time", LocalDateTime.now().toString());
            metadata.put("source", "user_upload");

            // 4. 分块
            //    PDF 已由 PdfStructureDocumentReader 按章节/页面切好，只做二级子分块
            //    非 PDF 仍按原文走完整分块
            List<Document> chunks;
            if (structuredDocs != null) {
                chunks = new ArrayList<>();
                for (Document doc : structuredDocs) {
                    // 每个章节/页面过一遍语义分块器，保证单块不超长
                    Map<String, Object> combinedMeta = new HashMap<>(metadata);
                    combinedMeta.putAll(doc.getMetadata());
                    Document merged = new Document(doc.getText(), combinedMeta);
                    List<Document> subChunks = semanticTextSplitter.apply(List.of(merged));
                    // 如果分块器没产出（段落太短），用原文档
                    if (subChunks.isEmpty()) {
                        chunks.add(merged);
                    } else {
                        for (Document sc : subChunks) {
                            sc.getMetadata().putAll(combinedMeta);
                        }
                        chunks.addAll(subChunks);
                    }
                }
            } else if ("semantic".equals(chunkStrategy)) {
                chunks = semanticTextSplitter.apply(List.of(new Document(text, metadata)));
            } else {
                chunks = tokenTextSplitter.apply(List.of(new Document(text, metadata)));
            }

            // 5. 写入 chunk 序号
            int total = chunks.size();
            for (int i = 0; i < total; i++) {
                chunks.get(i).getMetadata().put("chunk_index", i);
                chunks.get(i).getMetadata().put("chunk_total", total);
            }

            // 6. 存入 PgVector（自动 Embedding）
            vectorStore.add(chunks);

            // 7. MySQL 更新为 ready
            ragDocumentDao.updateStatusByDocumentId(RagDocument.builder()
                    .documentId(documentId)
                    .chunkCount(total)
                    .status("ready")
                    .updateTime(LocalDateTime.now())
                    .build());

            log.info("文档上传成功 documentId:{} agentId:{} chunks:{}", documentId, agentId, total);
            return documentId;

        } catch (Exception e) {
            log.error("文档处理失败 documentId:{}", documentId, e);
            ragDocumentDao.updateStatusByDocumentId(RagDocument.builder()
                    .documentId(documentId)
                    .chunkCount(0)
                    .status("failed")
                    .updateTime(LocalDateTime.now())
                    .build());
            throw new RuntimeException("文档处理失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<RagDocumentEntity> listDocuments(String agentId) {
        return ragDocumentDao.queryByAgentId(agentId).stream()
                .map(po -> RagDocumentEntity.builder()
                        .documentId(po.getDocumentId())
                        .agentId(po.getAgentId())
                        .fileName(po.getFileName())
                        .fileType(po.getFileType())
                        .chunkStrategy(po.getChunkStrategy())
                        .chunkCount(po.getChunkCount())
                        .status(po.getStatus())
                        .createTime(po.getCreateTime() != null ? po.getCreateTime().toString() : null)
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public void deleteDocument(String documentId) {
        // 1. 从 PgVector 删除所有该文档的 chunk
        if (pgVectorJdbcTemplate != null) {
            int deleted = pgVectorJdbcTemplate.update(
                    "DELETE FROM " + VECTOR_TABLE + " WHERE metadata->>'document_id' = ?",
                    documentId);
            log.info("PgVector 删除 chunks:{} documentId:{}", deleted, documentId);
        }

        // 2. 删除 MySQL 记录
        ragDocumentDao.deleteByDocumentId(documentId);
        log.info("文档删除完成 documentId:{}", documentId);
    }

    private boolean isPlainText(String fileType) {
        return Set.of("txt", "md", "csv", "json", "xml", "yaml", "yml", "log", "sql", "java", "py", "js", "ts", "html", "css").contains(fileType);
    }

}
