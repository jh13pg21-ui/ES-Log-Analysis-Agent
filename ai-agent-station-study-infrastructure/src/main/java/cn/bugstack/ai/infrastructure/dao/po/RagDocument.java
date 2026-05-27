package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * RAG 文档记录表 PO
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RagDocument {

    /** 主键 */
    private Long id;

    /** 文档唯一ID，关联 PgVector metadata.document_id */
    private String documentId;

    /** 归属 Agent ID */
    private String agentId;

    /** 原始文件名 */
    private String fileName;

    /** 文件类型：txt / md / pdf */
    private String fileType;

    /** 分块策略：fixed / semantic */
    private String chunkStrategy;

    /** 实际分块数量 */
    private Integer chunkCount;

    /** 状态：processing / ready / failed */
    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
