package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.entity.RagDocumentEntity;

import java.util.List;

/**
 * RAG 文档管理领域服务接口
 * <p>
 * Phase1 上传：文本提取 → 分块 → Embedding → 存 PgVector + MySQL
 * Phase2 检索：由 RagAnswerAdvisor 完成（混合检索 + RRF 重排）
 */
public interface IRagService {

    /**
     * 上传文档并向量化存储
     *
     * @param agentId       归属 Agent
     * @param fileName      原始文件名
     * @param fileType      文件后缀（txt/md/pdf）
     * @param content       文件字节内容
     * @param chunkStrategy 分块策略：fixed / semantic
     * @return documentId   文档唯一ID
     */
    String uploadDocument(String agentId, String fileName, String fileType, byte[] content, String chunkStrategy);

    /**
     * 查询某个 Agent 下所有已上传文档
     */
    List<RagDocumentEntity> listDocuments(String agentId);

    /**
     * 删除文档（同时清除 PgVector 中所有对应 chunk）
     */
    void deleteDocument(String documentId);

}
