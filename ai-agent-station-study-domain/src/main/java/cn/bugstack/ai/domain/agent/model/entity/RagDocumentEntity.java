package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 文档领域实体
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RagDocumentEntity {

    private String documentId;

    private String agentId;

    private String fileName;

    private String fileType;

    /** fixed / semantic */
    private String chunkStrategy;

    private Integer chunkCount;

    /** processing / ready / failed */
    private String status;

    private String createTime;

}
