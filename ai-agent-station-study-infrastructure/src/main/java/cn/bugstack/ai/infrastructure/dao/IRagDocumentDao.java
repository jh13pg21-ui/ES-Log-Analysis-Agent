package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagDocument;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * RAG 文档记录 DAO
 */
@Mapper
public interface IRagDocumentDao {

    int insert(RagDocument ragDocument);

    int updateStatusByDocumentId(RagDocument ragDocument);

    int deleteByDocumentId(String documentId);

    RagDocument queryByDocumentId(String documentId);

    List<RagDocument> queryByAgentId(String agentId);

}
