package cn.bugstack.ai.domain.agent.model.valobj.enums;

import cn.bugstack.ai.domain.agent.model.valobj.AiClientAdvisorVO;
import cn.bugstack.ai.domain.agent.service.armory.factory.element.MemoryAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.factory.element.RagAnswerAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.factory.element.SummaryBufferChatMemory;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 顾问类型枚举
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/19 09:02
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum AiClientAdvisorTypeEnumVO {

    CHAT_MEMORY("ChatMemory", "上下文记忆（内存模式）") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     JdbcTemplate pgVectorJdbcTemplate, ChatModel chatModel) {
            AiClientAdvisorVO.ChatMemory chatMemory = aiClientAdvisorVO.getChatMemory();
            return PromptChatMemoryAdvisor.builder(
                    MessageWindowChatMemory.builder()
                            .maxMessages(chatMemory.getMaxMessages())
                            .build()
            ).build();
        }
    },

    RAG_ANSWER("RagAnswer", "知识库") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     JdbcTemplate pgVectorJdbcTemplate, ChatModel chatModel) {
            AiClientAdvisorVO.RagAnswer ragAnswer = aiClientAdvisorVO.getRagAnswer();
            return new RagAnswerAdvisor(vectorStore, SearchRequest.builder()
                    .topK(ragAnswer.getTopK())
                    .filterExpression(ragAnswer.getFilterExpression())
                    .build(), pgVectorJdbcTemplate, chatModel);
        }
    },

    MEMORY_ADVISOR("MemoryAdvisor", "长期记忆") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     JdbcTemplate pgVectorJdbcTemplate, ChatModel chatModel) {
            AiClientAdvisorVO.MemoryAnswer cfg = aiClientAdvisorVO.getMemoryAnswer();
            return new MemoryAdvisor(vectorStore, chatModel,
                    cfg.getAgentId(), cfg.getConsolidateEveryN(), cfg.getTopK());
        }
    },

    SUMMARY_BUFFER_MEMORY("SummaryBufferMemory", "摘要缓冲短期记忆") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, VectorStore vectorStore,
                                     JdbcTemplate pgVectorJdbcTemplate, ChatModel chatModel) {
            AiClientAdvisorVO.SummaryBufferMemory cfg = aiClientAdvisorVO.getSummaryBufferMemory();
            return PromptChatMemoryAdvisor.builder(
                    new SummaryBufferChatMemory(chatModel, cfg.getMaxRawMessages(), cfg.getCompressSize())
            ).build();
        }
    }

    ;

    private String code;
    private String info;

    private static final Map<String, AiClientAdvisorTypeEnumVO> CODE_MAP = new HashMap<>();

    static {
        for (AiClientAdvisorTypeEnumVO enumVO : values()) {
            CODE_MAP.put(enumVO.getCode(), enumVO);
        }
    }

    /**
     * 策略方法：创建顾问对象
     *
     * @param aiClientAdvisorVO    顾问配置
     * @param vectorStore          向量存储
     * @param pgVectorJdbcTemplate PgVector JdbcTemplate（用于 BM25，可为 null）
     */
    public abstract Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO,
                                          VectorStore vectorStore,
                                          JdbcTemplate pgVectorJdbcTemplate,
                                          ChatModel chatModel);

    public static AiClientAdvisorTypeEnumVO getByCode(String code) {
        AiClientAdvisorTypeEnumVO enumVO = CODE_MAP.get(code);
        if (enumVO == null) {
            throw new RuntimeException("err! advisorType " + code + " not exist!");
        }
        return enumVO;
    }

}
